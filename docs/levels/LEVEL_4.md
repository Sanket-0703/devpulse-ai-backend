# Level 4 — Connect GitHub

**Goal:** link DevPulse users to GitHub through the **DevPulse GitHub App**: "Sign in with GitHub", "Connect GitHub" for existing accounts, installing the app on chosen repositories, connection status, and disconnecting. GitHub tokens are encrypted at rest and never leave the backend.

**What you can do at the end of Level 4:**
- On *Sign in* or *Create account*: **Continue with GitHub**. A new account is created, or you're signed into the one already linked.
- In *Settings → GitHub*: **Connect GitHub**, see `@username` and "Connected since", **Install on repositories** or **Manage repositories**, and **Disconnect** (with confirmation).
- A GitHub-only account can **set its first password** in *Security*.

> Builds on [LEVEL_3.md](LEVEL_3.md). Setup of the app itself: [GITHUB_APP_SETUP.md](../GITHUB_APP_SETUP.md).

---

## 1. Why a GitHub App

| | OAuth App | **GitHub App (chosen)** |
|---|---|---|
| Repository access | The `repo` scope = read/write on *every* repo you can reach | Fine-grained: *Contents: read*, *Pull requests: read & write*, *Metadata* |
| Which repos | All | The ones you pick when installing |
| Token lifetime | Never expires | 8 hours, refreshed with a 6-month refresh token |
| Webhooks (Level 9) | Created per repo through the API | One, built into the app |
| Review comments (Level 9) | Posted as you | Posted as `<app>[bot]` |

A GitHub App has **two identities**, and Level 4 uses the first:
1. **User-to-server tokens:** "act as this user". Obtained through OAuth (authorize → callback → code → token). Used to identify the user, list their installations, and read their emails.
2. **Installation tokens:** "act as the app on the repos it's installed on". Created by signing a JWT with the app's private key. These come in Level 5.

---

## 2. The flows

All three end at **one callback** (`GET /api/github/oauth/callback`), which always redirects the browser to the frontend's `/github/callback?result=…`.

```
SIGN IN (login page, signed out)
 browser → GET /api/auth/github ─302→ github.com/login/oauth/authorize?client_id&redirect_uri&state
         ← user approves ←
         → GET /api/github/oauth/callback?code&state
            backend: code → token, GET /user, /user/installations, /user/emails
                     linked? sign in : create account
            ─302→ localhost:5173/github/callback?result=signed-in&new=true   + Set-Cookie (refresh token)
 frontend: page load → POST /api/auth/refresh (cookie) → signed in → /dashboard

CONNECT (Settings, signed in)
 frontend → POST /api/github/connect (Bearer) → { url }      ← state bound to this user
 browser  → url (github authorize) → callback → link → ─302→ /github/callback?result=connected → /settings/github

INSTALL (Settings, connected)
 frontend → POST /api/github/install → { url: github.com/apps/<slug>/installations/new?state }
 browser  → choose repositories → GitHub also authorizes (option "Request user authorization
            during installation") → callback with code + installation_id → link updated
```

**Why `connect` is a POST that returns a URL instead of a link:** the browser's navigation to GitHub can't carry the `Authorization` header. So the signed-in app first asks the backend for a URL whose `state` is bound to the user, then navigates there.

### The `state` parameter (CSRF protection)

Without it, an attacker could send you a link to our callback carrying *their* GitHub code, linking *their* GitHub to *your* account, or signing you into theirs (login CSRF). DevPulse:
- creates 256 random bits per flow and stores `flow:userId` under it in **Redis for 10 minutes**;
- reads it back with **`GETDEL`** (read-and-delete in one step), so each state works **once**;
- treats a callback with no valid state as an error, with one narrow exception: GitHub's own redirect after installing or changing repositories (`setup_action`). That may only *refresh* an existing link, **never create one or sign anyone in**.

### Security decisions

| Risk | Decision |
|---|---|
| **Pre-account takeover:** someone registers your email in DevPulse (emails aren't verified), then you "Sign in with GitHub" and get linked into *their* account | Never auto-link by email. If the GitHub email already belongs to a DevPulse account → `email_exists`: sign in with the password and connect from Settings |
| GitHub account with no verified email | Refused (`no_verified_email`): the account needs a trustworthy email |
| One GitHub account on two DevPulse accounts | Refused (`github_account_in_use`) |
| A database leak exposes GitHub tokens | Tokens are **AES-256-GCM encrypted**; the key lives in `.env`, not the database |
| Tokens leaking through the API | No response ever contains a token; the connection status has username, avatar and installation only |
| Locking yourself out | Disconnect is refused (409) for an account whose only way in is GitHub |
| A slow GitHub tying up the database | GitHub HTTP calls happen *outside* database transactions; 5 s connect / 10–15 s read timeouts |

---

## 3. Database: `V5__github_app_connection.sql`

1. **Deletes existing GitHub data** (pull requests, repositories, accounts). The old rows held *plaintext* tokens that SQL can't encrypt. Development data only; users connect again.
2. `github_accounts`: `refresh_token` becomes optional; token expiry and `connected_at` become `TIMESTAMPTZ`; new `installation_id BIGINT`.
3. `users.password` becomes nullable: accounts created with "Sign in with GitHub" start without one.

---

## 4. Backend, class by class

### 4.1 Configuration

**`config/GitHubProperties.java`** (record, prefix `github.`): `appId`, `appSlug`, `clientId`, `clientSecret`, `redirectUri`, `privateKeyPath` (Level 5), `webUrl` (`https://github.com`), `apiUrl` (`https://api.github.com`). The two URLs are configurable **so tests can point them at WireMock**. It replaces `GitHubOAuthProperties`; the old HMAC `stateSecret` is gone (random state in Redis needs no signature).

**`config/FrontendProperties.java`**: `app.frontend.url` (default `http://localhost:5173`), where the callback redirects to.

**`application.properties`**: the `github.*` and `app.frontend.url` settings, plus `app.security.token-encryption-key=${TOKEN_ENCRYPTION_KEY}`, with no default, so the app refuses to start without a key.

### 4.2 Encryption at rest

**`security/TokenEncryptor.java`**
- The key is `TOKEN_ENCRYPTION_KEY` (base64 of 32 random bytes); the constructor fails fast if it isn't valid base64 or isn't exactly 32 bytes (AES-256).
- **`encrypt(plaintext)`**: a new random 12-byte IV → AES/GCM/NoPadding with a 128-bit tag → `"v1:" + base64(iv ‖ ciphertext ‖ tag)`.
  - The random IV means the same token encrypts differently each time, so equal tokens can't be spotted.
  - **GCM is authenticated:** a modified value fails to decrypt (`AEADBadTagException` → "tampered with or the key changed") instead of silently returning garbage.
  - The `v1:` prefix leaves room for key rotation later.
- **`decrypt(stored)`**: rejects values without the prefix, splits the IV off, decrypts.

**`security/EncryptedStringConverter.java`**: a JPA `AttributeConverter<String, String>`. Fields marked `@Convert(converter = EncryptedStringConverter.class)` are encrypted on write and decrypted on read, so code using the entity only sees plaintext while the column only holds ciphertext. It's a Spring `@Component`: Spring Boot lets Hibernate create converters through Spring, so the converter can receive the configured `TokenEncryptor`.

### 4.3 Entities
- **`GitHubAccount`**:
  - `accessToken` and `refreshToken` are encrypted; `refreshToken` and its expiry are optional.
  - Expiry and `connectedAt` are `Instant`s.
  - New `installationId`.
  - Removed Lombok `@AllArgsConstructor`, an unused constructor that took every field, tokens included.
- **`User.password`**: nullable, documented.

### 4.4 OAuth state: `GitHubOAuthStateService` + impl (rewritten)
- `enum Flow { SIGN_IN, CONNECT }`, `record State(flow, userId)`.
- `create(flow, userId)` → 32 random bytes, base64url → Redis `oauth:state:<state>` = `"CONNECT:42"` (or `"SIGN_IN:"`) with a 10-minute TTL.
- `consume(state)` → `RedisService.getAndDelete` (new; Redis `GETDEL`) → `Optional<State>`.

### 4.5 Talking to GitHub

**`client/GitHubOAuthClient.java`** (new): the app's OAuth side.

| Method | What it does |
|---|---|
| `authorizeUrl(state)` | `{webUrl}/login/oauth/authorize?client_id&redirect_uri&state`, built with `UriComponentsBuilder` and properly encoded (the old code concatenated strings) |
| `installUrl(state)` | `{webUrl}/apps/{slug}/installations/new?state` |
| `installationSettingsUrl(id)` | `{webUrl}/settings/installations/{id}`, where you choose repositories |
| `exchangeCode(code)` | `POST /login/oauth/access_token` (form: client id, secret, code, redirect_uri) |
| `refresh(refreshToken)` | the same endpoint with `grant_type=refresh_token` |
| `revokeGrant(token)` | `DELETE {apiUrl}/applications/{client_id}/grant` with HTTP Basic auth (client id:secret) and `{"access_token": …}`: removes DevPulse's authorization entirely |

**A GitHub quirk handled in `requestToken`:** the token endpoint reports errors (bad or expired code) with **HTTP 200 and an `error` field**, so a missing `access_token` or a present `error` throws `GitHubIntegrationException`.

**`client/GitHubApiClient` + `GitHubApiClientImpl`** (reworked):
- One `RestClient` built in the constructor from `apiUrl`, with timeouts and GitHub's recommended default headers (`Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2022-11-28`). The old code repeated these on every call.
- New: `getEmails(token)` (`/user/emails`) and `getUserInstallations(token)` (`/user/installations`).
- `call(...)` turns any `RestClientException` into `GitHubIntegrationException("github_error")`.
- The repository and pull-request methods stay for Levels 5–6.

**DTOs:**
- `GitHubTokenResponse` gained `error` and `errorDescription`.
- New records: `GitHubEmailResponse(email, primary, verified)`, and `GitHubInstallationsResponse(totalCount, installations[id, appId, account])`, which ignores unknown fields.

**`exception/GitHubIntegrationException`** carries a machine-readable `reason` (e.g. `github_error`, `github_reconnect`).

### 4.6 `GitHubTokenServiceImpl` (rewritten)
- `getValidAccessToken(account)`: valid if the expiry is unknown (expiry disabled on the app) or more than **5 minutes** away (`EXPIRY_MARGIN`, so a token doesn't expire mid-request); otherwise `refreshAccessToken`.
- `refreshAccessToken`: no refresh token, or an expired one → `GitHubIntegrationException("github_reconnect")`; otherwise refresh and save.
- `applyTokens(account, response, now)` (static, shared): turns `expires_in` seconds into `Instant`s.

### 4.7 `GitHubConnectionService` + impl: the heart of the level

**Starting a flow:** `signInUrl()`, `connectUrl(userId)`, `installUrl(userId)`. Each creates a state and builds the GitHub URL. Install uses a CONNECT state, because installing also authorizes.

**`handleCallback(params)`** never throws. It wraps `processCallback` and turns `GitHubIntegrationException` into `Failed(reason)` and anything unexpected into `Failed("github_error")` (logged). The browser must always land on the frontend, never on a JSON error page.

`processCallback`:
1. GitHub sent `error` (e.g. the user clicked *Cancel* → `access_denied`) → `Failed`.
2. No `code` → `Failed("github_error")`.
3. `consume(state)`. Missing or invalid: if GitHub's `setup_action` is present → `refreshExistingLink`, otherwise → `Failed("invalid_state")`.
4. `fetchIdentity(code)`: exchange the code, `GET /user`, and find **our** installation (`/user/installations`, filtered by `app_id == github.app-id`, because the user may have other apps installed). **No database transaction is open during these HTTP calls.**
5. `SIGN_IN` → `signIn`; `CONNECT` → `connect`. Each runs its database work inside a `TransactionTemplate`, a short programmatic transaction.

**`signIn(identity)`**
- GitHub id already linked → update profile, tokens and installation → `SignedIn(refreshTokenService.issue(user), newAccount=false)`.
- Otherwise: `primaryVerifiedEmail` (verified only, primary first, normalized); none → `no_verified_email`.
- Email already used by a DevPulse account → **`email_exists`** (no auto-linking; see section 2).
- Otherwise create the `User` (display name = GitHub name or login, capped at 100 characters; `password = null`) plus the `GitHubAccount` → `SignedIn(…, newAccount=true)`.

**`connect(userId, identity)`**
- This GitHub account is linked to another user → `github_account_in_use`.
- This user is linked to a *different* GitHub account → `already_connected`.
- Otherwise create or update the link → `Connected`.

**`refreshExistingLink(identity)`** (no-state redirect from GitHub): updates the link if this GitHub user is already linked (→ `Connected`), otherwise `not_connected`. It never signs anyone in.

**`applyIdentity(account, identity)`**: copies the login, avatar, installation and tokens; sets `connectedAt` only the first time.

**`status(userId)`** → `GitHubConnectionResponse`: `connected`, `username`, `avatarUrl`, `profileUrl`, `connectedAt`, `installed`, `manageRepositoriesUrl`. No tokens.

**`disconnect(userId)`**: idempotent (nothing to do if not connected). An account without a password → `ConflictException` (409). Otherwise, best effort: revoke the grant on GitHub (refreshing the token first if needed; failures are logged, not fatal). Then unlink and delete the account, whose synced repositories cascade away.

### 4.8 `controller/GitHubController.java` (rewritten)

| Endpoint | Auth | Response |
|---|---|---|
| `GET /api/auth/github` | public (browser link) | 302 → GitHub authorize |
| `POST /api/github/connect` | Bearer | `{ url }` |
| `POST /api/github/install` | Bearer | `{ url }` |
| `GET /api/github/oauth/callback` | public (GitHub redirect) | 302 → `{frontend}/github/callback?result=signed-in&new=…` + **Set-Cookie** · `?result=connected` · `?result=error&reason=…` |
| `GET /api/github/connection` | Bearer | `GitHubConnectionResponse` |
| `DELETE /api/github/connection` | Bearer | 204 · 409 without a password |

The callback turns the **sealed** `CallbackResult` (`SignedIn | Connected | Failed`) into redirects with a Java 21 `switch` pattern match. The compiler checks every case is handled.

**Removed:** the old `GitHubOAuthController`, `GitHubOAuthService(Impl)`, `GitHubAccountService(Impl)`, `ConnectGitHubRequest`, `GitHubAccountResponse`, and `POST /api/github/connect` with a pasted token (it never worked: it didn't set the required refresh token).

**`SecurityConfig`**: `GET /api/auth/github` and the callback are public; the stale `/api/github/oauth/authorize` rule is gone.

### 4.9 Accounts without a password
- **`AuthServiceImpl.login`**: an account with no password always fails, still running bcrypt against the dummy hash so timing doesn't reveal it.
- **`AuthServiceImpl.changePassword`**: without a password, `currentPassword` isn't needed (this sets the first one); with one, a blank current password → "Current password is required".
- **`ChangePasswordRequest.currentPassword`** is optional in the DTO; the rule lives in the service.
- **`ProfileServiceImpl`**: changing the email without a password → "Set a password in Settings → Security before changing your email".
- **`UserResponse.hasPassword`**: the frontend shows the right forms.

### 4.10 Configuration and secrets
- **`.env.example`**: `GITHUB_APP_ID`, `GITHUB_APP_SLUG`, `GITHUB_CLIENT_ID/SECRET`, `GITHUB_REDIRECT_URI`, `GITHUB_PRIVATE_KEY_PATH`, `TOKEN_ENCRYPTION_KEY`.
- **Your `.env`** got a generated `TOKEN_ENCRYPTION_KEY`.
- **`.gitignore`**: `*.pem`.
- **`.gitleaks.toml`**: allowlists the test profile's encryption key (base64 of `test-only-key-0123456789abcdef01`).

### 4.11 Backend tests

**`TokenEncryptorTest`** (7):
- round trip, and the ciphertext doesn't contain the token;
- the same value encrypts differently each time;
- `null` stays `null`;
- a flipped bit in the tag → "tampered";
- a different key can't decrypt;
- plaintext in the column is rejected;
- the key must be valid base64 of 32 bytes.

**`GitHubConnectionIntegrationTest`** (17) runs the real app, PostgreSQL and Redis, with **GitHub replaced by WireMock**: a local HTTP server started in the test (`WireMockServer`, dynamic port). `@DynamicPropertySource` points `github.web-url`/`api-url` at it, and each test stubs the token, `/user`, `/user/emails`, `/user/installations` and `/applications/{id}/grant` endpoints. Browser steps use the JDK `HttpClient`, which doesn't follow redirects, so each `302`, `Location` and `Set-Cookie` is checked exactly as a browser would get them.

| Group | Tests |
|---|---|
| Sign in | creates the account and signs in (the redirect URL, the cookie, a refresh works, `hasPassword=false`, connection status with installation 555 and manage URL); **tokens encrypted in the DB** (`v1:`, plaintext not present, only *our* app's installation id); signing in again reuses the account; **never takes over an existing email** (`email_exists`, no cookie, nothing linked); needs a verified email; a GitHub-only account can set its first password and then log in with it; …and can't log in with any password before that |
| Bad callbacks | an unknown state, and a **reused state** → `invalid_state`; Cancel on GitHub → `access_denied`; GitHub's "HTTP 200 + error" for a bad code → `github_error` |
| Connect | links to the signed-in user (no cookie set); the install URL is bound to the user; a GitHub account can't belong to two users; GitHub's no-state "redirect on update" refreshes an existing link but never signs in |
| Disconnect | revokes on GitHub (WireMock verifies `DELETE /applications/test-client-id/grant` with Basic auth and the right token) and removes the link; refused (409) for a GitHub-only account; endpoints need a token |

Also: `AuthServiceImplTest` (+3: no-password login fails with the dummy hash; set the first password; a current password is required when one exists), `ProfileServiceImplTest` (+1: email change without a password), and `BackendApplicationIntegrationTest` (Flyway 1–5).

**Totals:** 101 backend tests; coverage **47% → 70%**. The old untested GitHub code is gone and the new code is covered.

---

## 5. Frontend, file by file

### 5.1 `lib/browser.ts`
`redirectTo(url)` → `window.location.assign(url)`. It's a separate module so tests can replace it with `vi.mock`: jsdom can't actually navigate to github.com, so tests assert *where* the app tried to go.

### 5.2 `features/github/api.ts`
- `GITHUB_SIGN_IN_URL` = `${API_BASE_URL}/api/auth/github`, used as a **plain link** (the browser follows the redirect chain).
- `fetchConnection()`, `startConnect()` → URL, `startInstall()` → URL, `disconnect()`.
- `githubConnectionKey` is the TanStack Query cache key.

### 5.3 `features/github/GitHubSignInButton.tsx` + `components/common/GitHubIcon.tsx`
An outline `Button asChild` wrapping an `<a href>`, plus an "or" divider; it sits above the email form on both *Sign in* and *Create account*. Lucide dropped brand logos, so `GitHubIcon` is GitHub's mark as an inline SVG using `currentColor`, which works in both themes.

### 5.4 `features/github/GitHubCallbackPage.tsx` (route `/github/callback`, outside the guards)
- While the session is still loading → spinner.
- `result=signed-in` + authenticated → `<Navigate to="/dashboard">` and a toast ("Welcome to DevPulse!" for new accounts, otherwise "Signed in with GitHub"). This works because `AuthProvider` ran `refreshSession()` on page load, and the cookie set by the callback's 302 was sent with it.
- `result=connected` → `/settings/github` and "GitHub connected".
- Errors → a card with the explanation and a way back: *Back to settings* if signed in, otherwise *Back to sign in*.
- The toasts use a **fixed `id`** so React StrictMode's double effect doesn't show them twice.

**`features/github/callbackReasons.ts`**: one message per `reason`, with a generic fallback for anything unknown.

### 5.5 `features/settings/pages/GitHubSettingsPage.tsx` (tab *GitHub*)
`useQuery(githubConnectionKey, fetchConnection)` with a **skeleton** while loading and an **error alert with Retry**.

| Component | Shows |
|---|---|
| `ConnectCard` | What DevPulse can and can't do, and **Connect GitHub** |
| `ConnectedCards` | *GitHub account*: avatar (`AvatarImage` with an icon fallback), `@username` linking to the profile, "Connected since", **Disconnect**. *Repositories*: "App installed" + **Manage repositories** (opens GitHub), or "Not installed…" + **Install on repositories** |
| `DisconnectButton` | An `AlertDialog` asking for confirmation; for an account without a password, a hint to set one first instead of the button |
| `useGitHubRedirect(start)` | A small hook: a `useMutation` that asks the backend for the URL, then `redirectTo(url)`; errors → toast. Used by both Connect and Install |

After a disconnect, `invalidateQueries` refetches the status, so the page switches back to *Connect GitHub*.

### 5.6 Settings changes
- **`SettingsLayout`**: new **GitHub** tab; `routes.tsx` adds `settings/github` and `/github/callback`.
- **`schemas.ts`**:
  - `profileSchema(currentEmail, hasPassword)`: without a password, changing the email is blocked with a message on the field;
  - `changePasswordSchema(hasPassword)`: without a password, no current-password field and no "must differ" rule.
- **`SecuritySettingsPage`**: for a GitHub-only account, the card becomes **Set a password** (no current-password field, "Set password" button, a different toast). On success `updateUser` flips `hasPassword` to true and the card becomes the normal *Change password*.
- **`ProfileSettingsPage`**: the current-password field only appears for accounts that have a password.
- **`settings/api.ts`**: `changePassword` returns the user (with the updated `hasPassword`).

### 5.7 Frontend tests

**`test/authMock.ts`** grew:
- `hasPassword` on users;
- `resetAuthMock({ githubOnly: true })`: the demo user has no password and GitHub connected;
- `setDemoGitHub(link | null)`;
- handlers for `GET/DELETE /api/github/connection` (409 without a password) and `POST /api/github/connect|install` (return fixed GitHub URLs);
- the password and profile handlers follow the no-password rules.

**`features/github/github.test.tsx`** (13), with `vi.mock('@/lib/browser')`:

| Group | Tests |
|---|---|
| Sign in with GitHub | the link on the sign-in and sign-up pages points at `…/api/auth/github` |
| Callback page | signed in → dashboard + welcome; connected → `/settings/github` + toast; error explained with *Back to sign in*; *Back to settings* when signed in; unknown reason → generic message |
| Settings → GitHub | Connect calls the backend and redirects to GitHub's URL; connected-not-installed shows `@octo-ada` and Install (redirects to the install URL); installed links to *Manage repositories*; Disconnect: confirm → toast → *Connect GitHub* again; a GitHub-only account sees the "set a password first" hint instead |
| GitHub-only account | sets its first password (no current field), then sees the normal form; must set a password before changing its email |

**Totals:** 46 frontend tests.

---

## 6. Tools added this level

- **WireMock** (test dependency): a fake GitHub for integration tests.
- **`http/devpulse.http`** + `http-client.env.json`: ready-made API requests for IntelliJ's HTTP Client (see IDE_GUIDE Level 4).
- **`docs/GITHUB_APP_SETUP.md`**: how to register the app.

---

## 7. Explain it in an interview

**Why a GitHub App instead of an OAuth App?**
Least privilege and scoping. An OAuth App needs the `repo` scope, which is read/write on every repository the user can reach, with tokens that never expire. A GitHub App asks only for the permissions we need, is installed on repositories the user chooses, uses 8-hour tokens with refresh, has a single built-in webhook, and comments as a bot. It's GitHub's recommended model.

**Walk me through "Sign in with GitHub".**
The browser hits our endpoint, which stores a random single-use state in Redis and redirects to GitHub. After approval GitHub redirects to our callback with a code; we consume the state, exchange the code for a user token, read the user, their verified email and our installation, then sign in or create the account. The callback sets our httpOnly refresh-token cookie and redirects to the SPA, which restores the session with a normal refresh call. The GitHub token itself never reaches the browser.

**What does the OAuth `state` protect against, and how is yours implemented?**
CSRF on the callback: an attacker getting you to complete *their* authorization, which links their GitHub to your account or signs you into theirs. Ours is 256 random bits stored server-side for 10 minutes with the flow and user id, deleted atomically on first use (`GETDEL`). Callbacks without a valid state never create a link or a session.

**Why not link a GitHub sign-in to an existing account with the same email?**
We don't verify emails at sign-up. An attacker could pre-register a victim's email; auto-linking would then sign the victim into the attacker's account, a pre-account-takeover. So an existing email requires signing in with the password and connecting explicitly.

**How are the GitHub tokens protected?**
AES-256-GCM through a JPA `AttributeConverter`: a random IV per value, an authenticated tag to detect tampering, a version prefix for rotation. The key comes from the environment, so a database dump alone is useless. Tokens are never serialized into any API response.

**How do you test OAuth without GitHub?**
WireMock stands in for github.com and api.github.com; the app's base URLs are configurable and overridden in the test. A non-redirect-following HTTP client drives the browser steps so each 302 and `Set-Cookie` can be asserted, including edge cases: a reused state, the user cancelling, GitHub's "HTTP 200 with an error" response, a duplicate email, and revocation on disconnect.

**Why are GitHub calls outside the database transaction?**
A transaction holds a database connection. If GitHub is slow, holding connections while waiting on the network can exhaust the pool and stall the whole app. So we fetch from GitHub first, then open a short transaction to persist.

---

## 8. Files in this level

**Backend, new:** `V5__github_app_connection.sql` · `config/GitHubProperties` · `config/FrontendProperties` · `security/TokenEncryptor` · `security/EncryptedStringConverter` · `client/GitHubOAuthClient` · `service/GitHubConnectionService` + `impl/GitHubConnectionServiceImpl` · `dto/UrlResponse` · `dto/github/{GitHubConnectionResponse,GitHubEmailResponse,GitHubInstallationsResponse}` · `exception/GitHubIntegrationException` · tests `TokenEncryptorTest`, `GitHubConnectionIntegrationTest`

**Backend, rewritten or changed:** `controller/GitHubController` · `client/GitHubApiClient(+Impl)` · `service/GitHubOAuthStateService(+Impl)` · `service/impl/GitHubTokenServiceImpl` · `service/RedisService(+Impl)` (`getAndDelete`) · `entity/GitHubAccount` · `entity/User` · `dto/UserResponse` · `dto/ChangePasswordRequest` · `dto/github/GitHubTokenResponse` · `service/impl/AuthServiceImpl` · `service/impl/ProfileServiceImpl` · `config/SecurityConfig` · `BackendApplication` · `application.properties` · `application-test.properties` · `pom.xml` (WireMock) · `.env.example` · `.gitignore` · `.gitleaks.toml` · tests `AuthServiceImplTest`, `ProfileServiceImplTest`, `BackendApplicationIntegrationTest`

**Backend, deleted:** `config/GitHubOAuthProperties` · `controller/GitHubOAuthController` · `service/GitHubOAuthService(+Impl)` · `service/GitHubAccountService(+Impl)` · `dto/github/ConnectGitHubRequest` · `dto/github/GitHubAccountResponse`

**Frontend, new:** `lib/browser.ts` · `components/common/GitHubIcon.tsx` · `features/github/{api,callbackReasons,GitHubSignInButton,GitHubCallbackPage}` · `features/settings/pages/GitHubSettingsPage.tsx` · `features/github/github.test.tsx`

**Frontend, changed:** `app/routes.tsx` · `features/auth/pages/{LoginPage,RegisterPage}` · `features/settings/{SettingsLayout,schemas,api}` · `features/settings/pages/{ProfileSettingsPage,SecuritySettingsPage}` · `lib/api/schema.d.ts` (regenerated) · `test/authMock.ts`

**Docs and tools:** `docs/levels/LEVEL_4.md` · `docs/GITHUB_APP_SETUP.md` · `http/devpulse.http` · `http/http-client.env.json` · `BUILD_PLAN.md` · `IDE_GUIDE.md` (Level 4)
