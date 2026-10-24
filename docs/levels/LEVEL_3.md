# Level 3 — Profile & Settings

**Goal:** signed-in users manage their own account: change their name and email, change their password, and sign out of every device. All of it secure by default: changing the email needs your password, and a password change really does end every other session, immediately.

**What you can do at the end of Level 3:** open *Settings* (sidebar or account menu) → **Profile**: edit your name; change your email (a *Current password* field appears); see "Member since". **Security**: change your password (you stay signed in here, other devices are signed out); *Sign out everywhere* (with a confirmation dialog).

> Builds on [LEVEL_2.md](LEVEL_2.md): refresh-token families, `revokeAll`, the JWT filter, the auth-aware fetch.

---

## 1. The design in one page

### Endpoints

| Endpoint | Body | Success | Errors |
|---|---|---|---|
| `GET /api/profile` | – | 200 `UserResponse` | 401 |
| `PUT /api/profile` | `{name, email, currentPassword?}` | 200 `UserResponse` | 400 (validation, or `currentPassword` missing/wrong when the email changes), 409 (email taken) |
| `PUT /api/profile/password` | `{currentPassword, newPassword}` | 200 `AuthResponse` + new refresh cookie | 400 (`currentPassword` wrong, `newPassword` too short or unchanged) |
| `POST /api/auth/logout-all` | – | 204 + cookie cleared | 401 |

All four need a valid access token.

### The problem with "sign out other devices"

Level 2 can already revoke refresh tokens. But another device also holds an **access token**, valid for up to 15 more minutes, and access tokens aren't stored anywhere, so there's no list to revoke. Options:

| Option | Problem |
|---|---|
| Blacklist every access token in Redis | We don't know the other devices' token ids |
| Wait for them to expire | A stolen session keeps working for up to 15 minutes after you change your password |
| **Per-user cutoff (chosen)** | – |

**Per-user cutoff:** the `users` table gets `tokens_valid_after`. Changing the password (or signing out everywhere) sets it to *now*. The JWT filter already loads the user on every request; it now also rejects any token whose `iat` (issued-at) is **before** the cutoff. One column, one comparison, no extra lookups, and every older token dies instantly.

```
10:00:00  A signs in           token A, iat 10:00:00
10:02:00  B signs in           token B, iat 10:02:00
10:05:30  B changes password   cutoff = 10:05:30
                               B gets new token, iat 10:05:30  ✓
10:06:00  A calls the API      iat 10:00:00 < cutoff  →  ✗ 401
          A tries to refresh   token revoked          →  ✗ 401
                               → A lands on Sign in
```

**Whole seconds:** JWT `iat` has no milliseconds, so the cutoff is truncated to whole seconds too. Otherwise the new token issued in the same request (iat 10:05:30) would look older than a cutoff of 10:05:30.750 and be rejected. The trade-off: a token issued *in that same second* on another device would survive. `logout-all` also blacklists the request's own token to cover that edge.

### Why a wrong current password is a 400, not a 401

A 401 means "your session isn't valid". The frontend's `authFetch` reacts to a 401 by refreshing the session and retrying. A wrong password in a form is a *form* error: the session is fine. So it's a **400 with a field error**, shown next to the *Current password* input.

### Why changing the email needs the password

Whoever controls the account's email controls the account (password resets come later). If someone walks up to a signed-in laptop, they shouldn't be able to quietly move the account to their own address. Changing only the name needs no password.

---

## 2. Database: `V4__profile_and_session_cutoff.sql`

**Part 1: audit timestamps.** V1 created `created_at` / `updated_at` on `users`, `github_accounts`, `github_repositories` and `pull_requests`, but nothing ever filled them. V4:
1. Changes them to `TIMESTAMPTZ` (`USING created_at AT TIME ZONE 'UTC'` tells Postgres to read the old zone-less values as UTC).
2. Backfills `NULL`s: `created_at = now()`, `updated_at = created_at`. That's the best available value for existing rows.
3. Makes them `NOT NULL`.

**Part 2:** `ALTER TABLE users ADD COLUMN tokens_valid_after TIMESTAMPTZ;` `NULL` means no cutoff.

Flyway applies V4 automatically on the next start; your local database got it when the backend restarted.

---

## 3. Backend, class by class

### 3.1 `common/BaseEntity.java`: automatic timestamps

```java
@CreationTimestamp
@Column(name = "created_at", nullable = false, updatable = false)
private Instant createdAt;

@UpdateTimestamp
@Column(name = "updated_at", nullable = false)
private Instant updatedAt;
```

- **`@CreationTimestamp`** (Hibernate) sets the field when the entity is first inserted; **`@UpdateTimestamp`** sets it on every update. No code has to remember.
- `updatable = false` stops `created_at` from ever being overwritten.
- The type changed from `LocalDateTime` (no time zone: ambiguous) to **`Instant`** (a point on the UTC timeline), matching the new `TIMESTAMPTZ` columns.
- All four entities extend `BaseEntity`, so they all get this.

### 3.2 `entity/User.java`
New field `Instant tokensValidAfter` mapped to `tokens_valid_after`, with a JavaDoc explaining the cutoff.

### 3.3 `common/EmailAddresses.java`
`normalize(email)` → `email.trim().toLowerCase(Locale.ROOT)`. It was a private method in `AuthServiceImpl`; now both `AuthServiceImpl` and `ProfileServiceImpl` need it, so it moved into one shared place (**extract and reuse**). `Locale.ROOT` avoids language-specific lowercasing (in Turkish, `"I".toLowerCase()` is a dotless `ı`).

### 3.4 `exception/FieldValidationException.java` + handler
- `new FieldValidationException("currentPassword", "Current password is incorrect")` carries a **field name** and a message.
- `GlobalExceptionHandler.handleFieldValidation` turns it into the same 400 shape bean validation produces:
  ```json
  { "status": 400, "message": "Current password is incorrect",
    "errors": [{ "field": "currentPassword", "message": "Current password is incorrect" }] }
  ```
  so the frontend handles "the backend says this field is wrong" one way, whatever the cause.

### 3.5 DTOs

| DTO | Fields and rules |
|---|---|
| `ProfileUpdateRequest` | `name` (required, ≤ 100), `email` (required, valid, ≤ 255), `currentPassword` (optional; needed only when the email changes) |
| `ChangePasswordRequest` | `currentPassword` (required), `newPassword` (8–72, the same bcrypt limit as registration) |
| `UserResponse` | now also `createdAt` (`Instant`), shown as "Member since" |

`ProfileResponse` was deleted: `UserResponse` is the one shape for "a user" everywhere.

### 3.6 `service/ProfileService` + `ProfileServiceImpl`

Replaces the old `UserService` / `UserServiceImpl`, which had `RuntimeException("User not found")` (→ 500), case-sensitive email checks, and no password check for email changes.

**`getProfile(userId)`** → `UserResponse`, or `ResourceNotFoundException` (→ 404). Read-only transaction.

**`updateProfile(userId, request)`**

```
user = find or 404
newEmail = normalize(request.email)
if newEmail != user.email:
    requireCurrentPassword(user, request.currentPassword)   ← 400 on currentPassword
    if another user has newEmail (any case): 409
    user.email = newEmail
user.name = trimmed name
saveAndFlush  (unique-constraint race → 409)
```

- The comparison happens **after normalizing**, so `ADA@Example.com` → `ada@example.com` is not a change and needs no password.
- `existsByEmailIgnoreCaseAndIdNot` checks other users only, so your own email doesn't count as "taken".
- `saveAndFlush` writes immediately, so a database unique-constraint violation (two users grabbing the same email at once) is caught here and becomes a clean 409 instead of an error at commit time.

**`requireCurrentPassword(user, password)`**: blank → "Enter your current password to change your email"; wrong (bcrypt `matches`) → "Current password is incorrect". Both are `FieldValidationException`s on `currentPassword`.

### 3.7 `AuthService` / `AuthServiceImpl`: password and sessions

Password changes live in `AuthService` (not `ProfileService`) because they're about credentials and sessions: they need the refresh tokens and must issue new tokens.

**`changePassword(userId, request)` → `AuthResult`**
1. Current password wrong → `FieldValidationException("currentPassword", …)`.
2. New password equals the current one (checked with `matches` against the hash) → `FieldValidationException("newPassword", "New password must be different from the current one")`.
3. `user.setPassword(encode(newPassword))`.
4. `endAllSessions(user)`.
5. `issueTokens(user, refreshTokenService.issue(user))`: a **brand-new session** (a new refresh-token family) for the device that made the change, so it isn't signed out itself.

**`logoutEverywhere(userId)`** → `endAllSessions(user)`.

**`endAllSessions(user)`** (private)
```java
Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
user.setTokensValidAfter(now);              // older access tokens rejected
refreshTokenService.revokeAll(user.getId()); // every refresh token revoked
```
The order inside `changePassword` matters: `revokeAll` runs **before** the new refresh token is issued, so the new one survives.

**`findUser(userId)`** (private): shared lookup that throws 404.

### 3.8 `security/JwtService.java`
- `AccessTokenClaims` now includes **`issuedAt`**.
- `parse` rejects a token without `iat` as invalid. Ours always have one, and the cutoff check needs it.

### 3.9 `security/CustomUserPrincipal` and `CustomUserDetailsService`
The principal carries `tokensValidAfter`, copied from the user in `toPrincipal`. The filter already loads the principal on every request, so the cutoff costs no extra database query.

### 3.10 `security/JwtAuthenticationFilter.java`: the cutoff check

```java
CustomUserPrincipal principal = customUserDetailsService.loadUserById(claims.userId());

Instant cutoff = principal.getTokensValidAfter();
if (cutoff != null && claims.issuedAt().isBefore(cutoff)) {
    request.setAttribute(AUTH_ERROR_ATTRIBUTE, "Session has ended, please sign in again");
    return;
}
```

As in Level 2, the filter doesn't reject the request itself: it leaves it anonymous with a reason, and `JsonSecurityErrorHandler` answers 401 with that message.

### 3.11 Controllers

**`ProfileController`** (`/api/profile`, `@Tag("Profile")`)

| Method | Calls | Notes |
|---|---|---|
| `getProfile` | `profileService.getProfile(principal.getId())` | Reads the database, not token claims, so it reflects updates immediately |
| `updateProfile` | `profileService.updateProfile(...)` | `@Valid` runs bean validation first |
| `changePassword` | `authService.changePassword(...)` | Returns the new `AuthResponse` **and** `Set-Cookie` with the new refresh token |

The old `@PreAuthorize("hasAnyRole('USER','ADMIN')")` annotations were removed: `anyRequest().authenticated()` already requires a signed-in user, and every user has one of those roles.

**`AuthController.logoutEverywhere`** (`POST /api/auth/logout-all`): `authService.logoutEverywhere(id)`, then `authService.logout(null, bearerToken)` to also blacklist this request's own token (the same-second edge), then 204 with the clearing cookie.

### 3.12 `repository/UserRepository.java`
Now only: `findByEmailIgnoreCase`, `existsByEmailIgnoreCase`, `existsByEmailIgnoreCaseAndIdNot`. The case-sensitive methods are gone, so nobody can accidentally use them.

### 3.13 Backend tests

**`ProfileServiceImplTest`** (8, Mockito):
- a name-only change never checks the password;
- the same email in a different case isn't a change;
- an email change without a password, or with a wrong one, gives a field error on `currentPassword`;
- a taken email (any case) → 409 and nothing saved;
- the right password → the email is saved normalized;
- a unique-constraint race → 409;
- an unknown user → 404.

**`AuthServiceImplTest`** (+4, 12 in total):
- `changePassword` rejects a wrong current password, with no tokens touched;
- it rejects reusing the same password (field `newPassword`);
- success: a new hash, the cutoff set, `revokeAll` called, a new session returned;
- `logoutEverywhere` truncates the cutoff to whole seconds (with a clock at `.750`).

**`JwtServiceTest`** (+1, 9 in total): the round trip checks `issuedAt`; a token without `iat` is rejected.

**`ProfileIntegrationTest`** (10, real HTTP + PostgreSQL + Redis):
- the profile includes `createdAt`;
- a name change needs no password, and `/me` shows it immediately;
- an email change: missing or wrong password → 400 field errors; with the password → the login moves to the new email (the old one fails, and the existing token keeps working because it carries the user id);
- you can't take another account's email (409);
- a wrong current password is 400, not 401; a same or too-short new password → 400 on `newPassword`;
- **password change with two devices:** the other device's unexpired access token → 401 "Session has ended" and its refresh → 401; this device's *old* token → 401 but its *new* token and cookie work; only the new password logs in;
- **sign out everywhere:** both devices' tokens and cookies are dead; signing in again works;
- `logout-all` without a token → 401.

The two "two devices" tests wait for the next whole second before acting (`waitForNextSecond()`), because the cutoff has whole-second precision.

`BackendApplicationIntegrationTest` now expects Flyway versions `1, 2, 3, 4` and reads `/api/profile` as `UserResponse`.

**Totals:** 73 backend tests; coverage **42% → 47%**.

---

## 4. Frontend, file by file

### 4.1 Routes: `app/routes.tsx`

```
/settings  (SettingsLayout)
├── index    → <Navigate to="profile" replace />
├── profile  → ProfileSettingsPage
└── security → SecuritySettingsPage
```

- **`BUILT_PAGES`** (a `Set`) lists nav items that now have real pages; `NAV_ITEMS.filter(...)` skips them when generating `ComingSoonPage` routes. Each later level adds its path here.
- Child paths are **relative** (`'profile'`, not `'/settings/profile'`), and `Navigate to="profile"` resolves relative to `/settings`.
- The sidebar's *Settings* link stays highlighted on `/settings/profile` because `NavLink` matches by prefix.

### 4.2 `features/settings/SettingsLayout.tsx`
`PageHeader` "Settings", then a tab bar (`<nav aria-label="Settings sections">`) of `NavLink`s styled as underlined tabs (the active one gets `border-primary`), then `<Outlet />` in a `max-w-2xl` column. `SECTIONS` will grow with GitHub (Level 4) and Review preferences (Level 9).

### 4.3 `features/settings/api.ts`
- `updateProfile(body)` → `PUT /api/profile` → `User`.
- `changePassword(body)` → `PUT /api/profile/password`; stores the **new access token** from the response with `setAccessToken`. The browser stores the new refresh cookie by itself from `Set-Cookie`.

### 4.4 `features/settings/schemas.ts`
- `normalizeEmail` mirrors the backend's `EmailAddresses.normalize`.
- **`profileSchema(currentEmail)`** is a *function* that builds the schema, because one rule depends on the signed-in user: `.refine(values => normalizeEmail(values.email) === currentEmail || values.currentPassword !== '')`, with the error on `currentPassword`.
- **`changePasswordSchema`**: current required; new 8–72; confirmation matches; new ≠ current. Two `.refine`s, each attached to the right field with `path`.
- `ProfileValues = z.infer<ReturnType<typeof profileSchema>>` derives the type from a schema factory.

### 4.5 `lib/forms.ts`: `applyServerFieldErrors(error, setError, fields)`
If the error is an `ApiRequestError` with status 400, each backend field error whose name is in `fields` goes onto that input with `setError` (focusing the first). Returns `true` if any were applied, so the page can skip its generic alert. Used by `RegisterPage` (refactored to use it), `ProfileSettingsPage` and `SecuritySettingsPage`. Three copies of the same loop became one function.

### 4.6 `features/settings/pages/ProfileSettingsPage.tsx`

| Piece | How |
|---|---|
| Form setup | `useForm` with `zodResolver(schema)`, defaults from the current user; `schema` is wrapped in `useMemo` keyed on `user.email` so it's rebuilt only when the email changes |
| "Member since" | `Intl.DateTimeFormat(undefined, { dateStyle: 'long' })` formats `createdAt` in the browser's language and time zone ("24 October 2026") |
| Password field only when needed | `useWatch({ control, name: 'email' })` subscribes to the email input; `emailChanged` decides whether *Current password* renders. (`useWatch` instead of `watch()`: `watch` returns a new function each render, which React's compiler can't memoize; the linter flagged it.) |
| Save / Cancel | Both disabled until `formState.isDirty` (something differs from the defaults). *Cancel* = `reset()` back to the defaults. |
| Submit | Sends `currentPassword` only when the email changed. On success: `updateUser(updated)` (the account menu shows the new name immediately), `reset({…saved values})` (makes the form "clean" again, so the password field disappears and Save disables), and a `toast.success`. |
| Errors | 409 → under *Email* (focused); 400 field errors → `applyServerFieldErrors`; anything else → an alert at the top |

The `<form className="contents">`: shadcn's `Card` spaces its direct children (header, content, footer) with a flex gap. A `<form>` wrapper would be the only child and break that spacing; `display: contents` makes the form box disappear from layout while keeping its behaviour.

### 4.7 `features/settings/pages/SecuritySettingsPage.tsx`
Two cards, two small components in one file.

**`ChangePasswordCard`**: current / new / confirm (`PasswordInput` with show/hide), the "8 to 72 characters" hint. On success: `reset()` clears the fields and the toast says "Password changed. Your other devices have been signed out." Server field errors go next to the inputs.

**`SignOutEverywhereCard`**
- A destructive button opens a shadcn **`AlertDialog`** (Radix): a modal that traps focus and has role `alertdialog`, so screen readers announce it as something needing a decision. *Cancel* closes it; the action button calls `confirm()`.
- `confirm()` → `logoutEverywhere()` from the auth context → toast "Signed out on every device." Then the auth state becomes `anonymous`, so `<RequireAuth>` sends you to `/login` automatically; the page doesn't navigate itself.
- On failure: an error toast, and nothing changes locally (see 4.8).

### 4.8 Auth layer changes
- **`authContext.ts`**: the context gains `logoutEverywhere()` and `updateUser(user)`.
- **`AuthProvider.tsx`**:
  - `logoutEverywhere` = API call → clear the TanStack Query cache → `anonymous`.
  - `updateUser` replaces the user only if still authenticated (the functional `setState` form avoids stale state).
- **`features/auth/api.ts` → `logoutEverywhere()`**: `unwrap(POST /api/auth/logout-all)`, and only **after success** drops the access token. If the request fails, you must not *think* you signed out everywhere when you didn't, so nothing changes locally and the error surfaces as a toast.

### 4.9 Components
`components/ui/alert-dialog.tsx`: shadcn's AlertDialog, generated with `npx shadcn add alert-dialog`. The installer offered to overwrite our Prettier-formatted `button.tsx`; we declined, so only the new file was added.

### 4.10 Frontend tests

**`test/authMock.ts`** grew into a small fake of every account endpoint:
- accounts keyed by id: `DEMO_USER` (with `createdAt`) and **`OTHER_USER`** (for "email taken");
- `sessionUserId` (whose refresh cookie the browser holds) and an access-token → user map, so `/me`, `/profile` and refresh return the *current* account even after an email change;
- handlers for `PUT /api/profile` (the same password and 409 rules as the backend), `PUT /api/profile/password` (clears every token, then signs this device in again), and `POST /api/auth/logout-all`;
- `demoAccountPassword()`, for asserting a password change.

**`test/setup.ts`**: also `toast.dismiss()` after each test. sonner keeps toasts in module-level state that outlives a test's render, so one test's "Profile updated" showed up in the next test (found by a test that passed alone but failed in the full run).

**`features/settings/settings.test.tsx`** (11):

| Group | Tests |
|---|---|
| Settings | `/settings` opens Profile with the current values and "Member since"; the tabs switch to Security |
| Profile | Save is disabled until something changes; a name change saves without a password and the account menu updates; an email change asks for the password (missing → client error, wrong → server error, right → saved, field hides again); a taken email shows the 409 under *Email*; Cancel restores the saved values |
| Security | client checks (same as current, too short, mismatch); a wrong current password shows next to the field; success → toast, stored password changed, a **new access token**, fields cleared, still signed in; Sign out everywhere: the dialog appears, Cancel keeps you here, confirm → sign-in page with the toast |

`auth.test.tsx`: the registration test now signs up `katherine@example.com`, since `grace@example.com` is the mock's second account.

**Totals:** 33 frontend tests.

---

## 5. Verified in the browser

Against the real backend:
1. `/settings` → the Profile tab with "Member since 24 October 2026" (from the V4 backfill).
2. Changed the email → *Current password* appeared → saved → "Profile updated", and the field disappeared.
3. Signed in a **second device** from the terminal (`curl`), then changed the password in the browser. The second device's unexpired access token got `401 "Session has ended, please sign in again"`, its refresh got 401, and the old password failed to log in.
4. Reloaded the browser: still signed in, with the new session.
5. *Sign out everywhere* → confirm → `/login` with "Signed out on every device." No console errors.

---

## 6. Explain it in an interview

**How do you sign out other devices when access tokens are stateless JWTs?**
A per-user cutoff timestamp (`tokens_valid_after`). The JWT filter already loads the user on each request, so it compares the token's `iat` with the cutoff and rejects older tokens. A password change sets the cutoff and revokes all refresh tokens, then issues a fresh session for the current device. It's immediate, needs no list of issued tokens, and costs no extra query.

**Why truncate the cutoff to seconds?**
JWT `iat` is in whole seconds. With millisecond precision, the new token issued in the same request would look older than the cutoff and be rejected. The remaining same-second edge is covered by blacklisting the request's own token on sign-out-everywhere.

**Why does changing the email need the password, but the name doesn't?**
The email is the account's identity and recovery channel; changing it is a takeover vector if someone gets brief access to a signed-in device. The name is cosmetic.

**Why return 400 instead of 401 for a wrong current password?**
401 means the session is invalid; the client would refresh and retry. A wrong form value is a validation error on a specific field, so it's a 400 with that field named, and the UI shows it next to the input.

**How do you keep frontend and backend validation consistent?**
The backend is the authority (bean validation plus service rules) and always validates. The frontend mirrors the rules in zod schemas for instant feedback, and maps any backend field errors onto the same inputs with one helper, so a rule the client missed still appears in the right place.

**What did you refactor in this level, and how did you keep it safe?**
I replaced `UserService` with `ProfileService` (with correct 404s and case-insensitive emails), extracted email normalization into one utility, and extracted the server-field-error mapping used by three forms. IDE refactorings (rename, extract, safe delete) did the edits, and the unit plus integration suites (73 backend, 33 frontend) confirmed nothing broke.

---

## 7. Files in this level

**Backend, new:** `V4__profile_and_session_cutoff.sql` · `common/EmailAddresses` · `exception/FieldValidationException` · `service/ProfileService` + `impl/ProfileServiceImpl` · tests `ProfileServiceImplTest`, `ProfileIntegrationTest`

**Backend, changed:** `common/BaseEntity` · `entity/User` · `security/JwtService` · `security/JwtAuthenticationFilter` · `security/CustomUserPrincipal` · `security/CustomUserDetailsService` · `service/AuthService` + `impl/AuthServiceImpl` · `controller/ProfileController` · `controller/AuthController` · `dto/ProfileUpdateRequest` · `dto/ChangePasswordRequest` · `dto/UserResponse` · `exception/GlobalExceptionHandler` · `repository/UserRepository` · tests `AuthServiceImplTest`, `JwtServiceTest`, `AuthFlowIntegrationTest`, `BackendApplicationIntegrationTest`

**Backend, deleted:** `dto/ProfileResponse` · `service/UserService` · `service/impl/UserServiceImpl`

**Frontend, new:** `features/settings/{api,schemas,SettingsLayout}` · `features/settings/pages/{ProfileSettingsPage,SecuritySettingsPage}` · `lib/forms.ts` · `components/ui/alert-dialog.tsx` · `features/settings/settings.test.tsx`

**Frontend, changed:** `app/routes.tsx` · `features/auth/{api,authContext,AuthProvider}` · `features/auth/pages/RegisterPage.tsx` · `lib/api/schema.d.ts` (regenerated) · `test/authMock.ts` · `test/setup.ts` · `features/auth/auth.test.tsx`

**Docs:** `docs/levels/LEVEL_3.md` · `BUILD_PLAN.md` · `IDE_GUIDE.md` (Level 3)
