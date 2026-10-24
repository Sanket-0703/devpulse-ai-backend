# Level 5 — Repositories

**Goal:** show the user exactly the repositories they granted the DevPulse GitHub App, keep them in sync in the background, and let them choose which ones DevPulse should review.

**What you can do at the end of Level 5:**
- **Repositories** page: your granted repositories with badges (Private, Fork, Archived), language and last push. Search as you type, filter by "Review on/off", sort by name or by most recent push, page through long lists, and flip **AI review** per repository.
- **Sync now** with live progress ("Syncing with GitHub…" → "Synced just now"). A sync also starts by itself right after you connect GitHub or install the app.
- **Repository detail** page: details, the AI review switch, a link to GitHub.

> Builds on [LEVEL_4.md](LEVEL_4.md) (GitHub App, connection, encrypted tokens).

---

## 1. The big ideas

### Acting as the app: installation tokens

Level 4 used **user tokens** ("act as this user"). Listing the repositories the user *granted the app* needs the app's own identity:

```
private key (.pem)
   │  sign (RS256)
   ▼
app JWT  ── iss = App ID, valid 9 min ──►  POST /app/installations/{id}/access_tokens
                                            ◄── { token: "ghs_…", expires_at: +1 hour }
installation token ──►  GET /installation/repositories   (exactly the repos the user selected)
```

- The **app JWT** proves "I am the DevPulse app". Only the holder of the private key can create one.
- The **installation token** is scoped to one installation's repositories and permissions, and lasts **1 hour**.
- Creating a token costs a GitHub API call, so it's **cached in Redis, encrypted**, until 5 minutes before it expires.

### Sync in the background

A sync makes several GitHub calls and may take seconds, so it never runs inside a web request:

```
POST /api/github/repositories/sync ─► cooldown check (1/min) ─► lock (one sync at a time)
                                      status = RUNNING ─► 202 Accepted (returns immediately)
                                      │
                         background virtual thread
                                      ├─ installation token (Redis cache or GitHub)
                                      ├─ GET /installation/repositories (page by page, 100 each)
                                      ├─ one short transaction: upsert / delete, status = SUCCEEDED
                                      └─ on error: status = FAILED + a readable reason
frontend: polls GET /sync every 1.5 s while RUNNING, then refreshes the list
```

### Ownership everywhere

Every repository query goes through the signed-in user's GitHub account (`githubAccount.user.id = me`). Another user's repository id behaves exactly like a missing one (404), so ids can't be probed.

---

## 2. Database: `V6__repositories_and_sync.sql`

| Change | Why |
|---|---|
| Drop V1's unique constraint on `github_repositories.github_id`; add `UNIQUE (github_account_id, github_id)` | The same GitHub repo (e.g. an organization repo) can belong to several DevPulse users, each with their own review setting |
| `pushed_at TIMESTAMPTZ` | Sort by recent activity |
| `review_enabled BOOLEAN NOT NULL DEFAULT FALSE` | Opt-in per repository |
| `synced_at` → `TIMESTAMPTZ` | UTC like everything else |
| index on `github_account_id` | Every list query filters by it |
| `github_accounts.sync_status` (default `NEVER`), `last_synced_at`, `sync_error` | Sync state shown in the UI |

---

## 3. Backend, class by class

### 3.1 Entities
- **`GitHubRepository`**:
  - per-account uniqueness declared in `@Table(uniqueConstraints = …)`, matching the migration;
  - new `pushedAt`, `reviewEnabled`; `syncedAt` is an `Instant`;
  - removed the global `unique = true` on `githubId` and Lombok's all-arguments constructor.
- **`SyncStatus`** enum: `NEVER`, `RUNNING`, `SUCCEEDED`, `FAILED`.
- **`GitHubAccount`**: `syncStatus` (stored as text with `@Enumerated(STRING)`, so reordering the enum can't corrupt data), `lastSyncedAt`, `syncError`.

### 3.2 `security/GitHubAppJwtSigner.java`

**`createAppJwt()`** builds a JWT with `iss = App ID`, `iat = now − 60 s` (backdated so small clock differences with GitHub don't make it "not yet valid"), and `exp = now + 9 min` (GitHub allows 10 at most). It's signed **RS256** with the private key.

**Loading the key:** lazily on first use (double-checked locking with a `volatile` field), so the app starts, and tests run, without a key; only repository features need it. A missing path or unreadable file gives a clear `IllegalStateException`.

**`parsePem(pem)`: the PKCS#1 problem.** GitHub downloads keys in **PKCS#1** format (`BEGIN RSA PRIVATE KEY`), but Java's `KeyFactory` only reads **PKCS#8** (`BEGIN PRIVATE KEY`). A PKCS#8 key is just the PKCS#1 key wrapped in a small ASN.1 structure:

```
SEQUENCE {
  INTEGER 0                                   -- version
  SEQUENCE { OID 1.2.840.113549.1.1.1, NULL } -- "this is an RSA key"
  OCTET STRING { <the PKCS#1 key bytes> }
}
```

`wrapPkcs1InPkcs8` builds exactly that, and `derElement(tag, content)` writes each DER element (tag, length in short or long form, content). That avoids adding the BouncyCastle library for one conversion.

### 3.3 Installation tokens: `GitHubInstallationTokenService` + impl
- **`getToken(installationId)`**:
  1. Look in Redis at `cache:github:installation-token:{id}` and decrypt if found.
  2. Otherwise `apiClient.createInstallationToken(jwtSigner.createAppJwt(), id)`.
  3. Cache it **encrypted** (`TokenEncryptor`) with TTL = `expires_at − now − 5 min`.
  - A missing key or App ID becomes `GitHubIntegrationException("github_app_not_configured")`.
- **`invalidate(id)`** drops the cached token (used when GitHub rejects calls made with it).

### 3.4 GitHub API additions
- **`GitHubApiClient.createInstallationToken(appJwt, installationId)`** → `POST /app/installations/{id}/access_tokens`.
- **`GitHubApiClient.listInstallationRepositories(token, page, perPage)`** → `GET /installation/repositories`.
- **DTOs:**
  - `GitHubInstallationTokenResponse(token, expiresAt)`;
  - `GitHubInstallationRepositoriesResponse(totalCount, repositories)`;
  - `GitHubRepositoryResponse` gained `pushedAt` and ignores unknown fields (GitHub returns dozens).

### 3.5 Background execution: `config/BackgroundConfig.java`
`backgroundExecutor()` is `Executors.newVirtualThreadPerTaskExecutor()`. **Virtual threads** (Java 21) are cheap enough to start one per task, and while a sync waits on GitHub's network responses it doesn't hold an operating-system thread. `destroyMethod = "close"` lets running tasks finish when the app shuts down.

### 3.6 `service/RepositorySyncService` + impl: the sync

**`requestSync(userId)`**: "Sync now".
1. No GitHub account → `ConflictException` (409, "Connect GitHub first…"); app not installed → 409 too.
2. **Cooldown:** `setIfAbsent("cooldown:github:sync:{account}", 1 min)`. If the key already exists → `TooManyRequestsException` with the seconds left (from the key's TTL). The response is **429** with a `Retry-After` header.
3. `start(accountId)`, then returns the current status.

**`syncAfterConnect(githubUserId)`**: called by the Level 4 callback after a successful sign-in or connect. It starts a sync if the app is installed, **without** the cooldown. It never throws: connecting must not fail because the first sync couldn't start.

**`start(accountId)`**
- **Lock:** `setIfAbsent("lock:github:sync:{account}", 10 min)`. If a sync is already running, do nothing (the caller sees RUNNING). The 10-minute expiry frees the lock even if a sync dies without releasing it.
- Marks the account `RUNNING` in its own short transaction **before** starting the task, so the UI shows "Syncing…" immediately.
- `backgroundExecutor.execute(() -> run(accountId, lockKey))`.

**`run(accountId, lockKey)`** (background thread)
1. Reads the installation id (short transaction).
2. **`fetchAll(installationId)`, with no transaction open:** gets the token, then requests pages of 100 until a short page or `total_count` is reached (at most 50 pages = 5,000 repos). If GitHub fails, the cached token is invalidated before rethrowing.
3. One short transaction: `apply(...)`, then `SUCCEEDED`, `lastSyncedAt = now`, `syncError = null`.
4. Errors → `markFailed` with a readable message ("The GitHub App isn't fully configured…" or "GitHub didn't respond as expected…"). The details go to the log, not the user.
5. `finally`: release the lock.

**`apply(account, remote, now)`: the reconciliation.**
- Loads existing rows for the account into a map keyed by GitHub id.
- For every repository GitHub returned: remove it from the map (or create a new row), then copy the name, description (truncated to the 2,000-character column), flags, URL, default branch, language, `pushedAt` and `syncedAt`. **`reviewEnabled` is never touched**, so the user's choice survives every sync.
- Whatever is left in the map wasn't returned anymore (the user removed it from the app on GitHub). It's removed from the account's collection and deleted explicitly; its pull requests go with it via cascade.

**`status(userId)`** → `SyncStatusResponse(connected, installed, status, lastSyncedAt, error, repositoryCount)`.

**`failInterruptedSyncs()`**: `@EventListener(ApplicationReadyEvent)`. If the server stopped mid-sync, the account would otherwise show RUNNING forever, so on startup every `RUNNING` row becomes `FAILED` with "interrupted by a server restart" (one bulk `@Modifying` query).

### 3.7 `service/RepositoryService` + impl: list, detail, toggle

**`list(userId, query, reviewEnabled, page, size, sort)`** builds a **JPA Specification**, a composable query condition:
- **`ownedBy(userId)`:** `githubAccount.user.id = userId`.
- **`matches(query)`:** case-insensitive `LIKE` on `fullName` **or** `description` (`coalesce` so a null description doesn't break the `OR`). `escapeLike` makes `%`, `_` and `\` match literally, so searching "50%" doesn't mean "anything starting with 50".
- **`reviewEnabledIs(flag)`:** an optional filter. Returning `null` from a spec means "no condition".
- **Sorting:**
  - `NAME` → `Sort.by(fullName)`.
  - `PUSHED` → `orderByPushedNullsLast()`: newest push first, never-pushed repos last, then name. It's built into the query because Spring Data's Criteria support can't do "nulls last" (an integration test caught this as a 500). The spec skips `ORDER BY` for the **count** query that paging also runs.
- **Paging:** page ≥ 0, size clamped to 1–100.

**`get` / `setReviewEnabled`** use `findOwned`, which is `findByIdAndGithubAccountUserId`, and throws a 404 `ResourceNotFoundException` both for "doesn't exist" and "isn't yours".

### 3.8 DTOs and controller
- **`PageResponse<T>(content, page, size, totalElements, totalPages)`:** our own page shape. Spring's `Page` JSON layout is internal and has changed between versions. `PageResponse.of(page, mapper)` converts.
- **`RepositoryResponse`:** id, githubId, name, fullName, description, isPrivate, fork, archived, htmlUrl, defaultBranch, language, pushedAt, syncedAt, reviewEnabled.
- **`ReviewSettingRequest(@NotNull Boolean reviewEnabled)`:** `Boolean`, not `boolean`, so a missing field is a 400 instead of silently `false`.
- **`SyncStatusResponse`**, **`TooManyRequestsException`** (→ 429 + `Retry-After` in `GlobalExceptionHandler`).

**`GitHubRepositoryController`** (`/api/github/repositories`):

| Endpoint | Does |
|---|---|
| `GET ?q&reviewEnabled&page&size&sort` | One page of your repositories |
| `GET /{id}` | One of yours, otherwise 404 |
| `PATCH /{id}` `{reviewEnabled}` | Turn AI review on/off |
| `GET /sync` | Sync status |
| `POST /sync` | Start a sync: 202, or 409 / 429 |

Removed: `GET /github/{githubId}`, `GET /account/{accountId}` (anyone could read any account's repos) and `DELETE /github/{githubId}`, all returning entities; plus the old `GitHubRepositoryService(Impl)`, `GitHubRepositoryCacheDto`, and the repository half of `GitHubCacheService`. The pull-request half stays until Level 6.

### 3.9 Other changes
- **`RedisService.setIfAbsent(key, value, ttl)`**: Redis `SET NX` with an expiry, the building block for the lock and the cooldown.
- **`RedisKeys`**: `cache:github:installation-token:`, `lock:github:sync:`, `cooldown:github:sync:`.
- **`GitHubConnectionServiceImpl.syncIfLinked`**: after a successful callback (transaction committed), starts the first sync.

### 3.10 Backend tests

**`GitHubAppJwtSignerTest`** (4) generates an RSA key in the test:
- the JWT verifies with the public key, with `iss`, the backdated `iat` and the 9-minute `exp`;
- **PKCS#1 and PKCS#8 keys both parse to the same key** (the test builds the PKCS#1 form by unwrapping Java's PKCS#8 export);
- missing key path or file → clear errors;
- garbage → "not a valid RSA key".

**`RepositoryIntegrationTest`** (10): real app, PostgreSQL and Redis, with GitHub as WireMock and a **real RSA key** generated per run (its path injected with `@DynamicPropertySource`). Each test signs in with a random fake GitHub identity, and background syncs are awaited with **Awaitility** (polls `GET /sync` until SUCCEEDED or FAILED).

| Test | Proves |
|---|---|
| connecting syncs in the background | 2 repos, fields mapped, review off by default; the token request's JWT **verifies against the app's public key** with `iss = 424242` |
| token cached between syncs | two syncs → exactly one token request |
| once a minute | second manual sync → 429 + `Retry-After` (sent with the JDK HttpClient, because TestRestTemplate's Apache client honours `Retry-After` and silently retried a minute later) |
| resync | removed repo deleted, changed description updated, **review setting and id kept** |
| failed sync | GitHub 500 → FAILED with the friendly reason |
| not connected | status `connected=false`, POST → 409 |
| two users, same repo | separate rows (the new per-account constraint) |
| list | paging (2+1), search on name and description, literal `%`, PUSHED order with nulls last, review filters |
| ownership | another user: GET and PATCH → 404, empty list |
| toggle validation | `{}` → 400 |

**Totals:** 115 backend tests; coverage **70% → 81%**, past the resume's 80% target.

---

## 4. Frontend, file by file

### 4.1 `features/repositories/api.ts`
- **Types** from the generated schema: `Repository`, `RepositoryPage`, `SyncStatus`.
- **`RepositoryListParams { q, filter: 'all'|'on'|'off', sort: 'NAME'|'PUSHED', page }`**, with `PAGE_SIZE = 20`.
- **`repositoryKeys`:** TanStack Query keys that all start with `'repositories'`, so one `invalidateQueries({ queryKey: ['repositories'] })` refreshes everything.
- **Calls:** `listRepositories` (maps the filter to `reviewEnabled=true|false|omitted`), `getRepository`, `setReviewEnabled`, `getSyncStatus`, `startSync`.

### 4.2 `features/repositories/hooks.ts`

| Hook | How it works |
|---|---|
| `useSyncStatus()` | `refetchInterval` is a **function**: 1.5 s while the status is `RUNNING`, otherwise off. A `useRef` remembers the previous status; when it changes from RUNNING to anything else, all repository queries are invalidated (and "Repositories synced" is shown on success) |
| `useStartSync()` | Mutation; on success puts the returned status straight into the cache (starting the polling), on error a toast (e.g. the 429 message) |
| `useRepositories(params, enabled)` | `placeholderData: keepPreviousData` keeps the current page on screen while the next loads (the list dims instead of flashing a skeleton); `enabled` only when connected + installed |
| `useRepository(id)` | Detail; `retry: false` so a 404 shows at once |
| `useToggleReview()` | **Optimistic update:** `onMutate` cancels in-flight repository queries, then flips the flag in every cached list page (`setQueriesData`) and the detail; `onError` flips it back and shows the error; `onSettled` refetches the lists (a "Review on" filter may need to drop the row) |

### 4.3 `features/repositories/components.tsx`
- **`SyncControl`:** the status text ("Syncing with GitHub…", "Last sync failed: …", "Synced 5 minutes ago", "Not synced yet") in an `aria-live` region, plus **Sync now** (spinner and disabled while running).
- **`RepositoryBadges`:** Private / Fork / Archived.
- **`ReviewSwitch`:** shadcn `Switch` (Radix, `role="switch"`, `aria-checked`) labelled "AI review for owner/name", wired to `useToggleReview`.

### 4.4 `features/repositories/pages/RepositoriesPage.tsx`

**State in the URL: `useListParams()`.** Search, filter, sort and page are read from `?q=&filter=&sort=&page=` (page is 1-based in the URL, 0-based in the API), and `update(changes)` writes them back with `replace: true`. Changing a filter resets to page 1, and defaults are left out to keep URLs clean. Reload, Back/Forward and shared links all show the same view.

**Search box:**
- the input's own state updates on every keystroke;
- `useDebouncedCallback` writes it to the URL 300 ms after typing stops, which is what triggers the request;
- when the URL changes on its own (Back), the "adjust state during render" pattern copies it into the input.

An earlier version used an effect on the debounced value. The linter flagged its missing dependencies, and the real bug behind the warning was that Back would have been overwritten. A test now covers it.

**Screens by sync status:**
- loading → skeleton;
- not connected → "Connect GitHub first" (link to Settings → GitHub);
- not installed → "Install the DevPulse app";
- otherwise the toolbar and the list.

**Toolbar:** search input with an icon, plus two `SegmentedControl`s (a `role="group"` of buttons with `aria-pressed`): *All / Review on / Review off* and *Name / Recently pushed*.

**`RepositoryList`:**
- skeleton or an error with Retry;
- no results → "No repositories match" (when filtering), the skeleton (while syncing), or "No repositories yet";
- otherwise a card of `RepositoryRow`s (name link, badges, description clamped to one line, language, "Pushed 3 days ago", the AI review switch);
- pagination: "Page X of Y · N repositories", Previous/Next.

### 4.5 `features/repositories/pages/RepositoryDetailPage.tsx`
- Back link, `h1` full name + badges, description, **Open on GitHub**.
- *AI review* card with the switch and "Review on/off".
- *Details*: default branch, language, last push, last synced, in a `<dl>`.
- A placeholder for pull requests (Level 6).
- Loading skeleton. A 404 → "Repository not found" + Back to repositories; other errors → a generic message.

### 4.6 Small utilities
- **`lib/time.ts` → `timeAgo(iso)`**: "3 days ago", "yesterday", "just now", using `Intl.RelativeTimeFormat` in the browser's language.
- **`hooks/useDebouncedCallback.ts`**: delays calls until they stop for 300 ms. It always calls the latest callback (kept in a ref) and cancels a pending call on unmount.
- **`components/ui/switch.tsx`**: shadcn Switch.
- **`app/routes.tsx`**: `/repositories` and `/repositories/:id`; `'/repositories'` added to `BUILT_PAGES`.
- **`app/AppProviders.tsx`**: `ReactQueryDevtools` when `import.meta.env.DEV` (the package also exports a no-op in production; checked: absent from the production bundle).

### 4.7 Frontend tests
**`test/repositoriesMock.ts`**: an in-memory fake of `/api/github/repositories*`:
- filtering, sorting and paging like the backend;
- a started sync reports RUNNING once, then finishes on the next poll with `setNextSyncResult(...)`;
- the second sync gets a 429;
- `failNextReviewToggle()` → 500;
- `storedRepository(id)` to assert what was saved.

`authMock` now exports `authenticated`, `error` and `Account` for it.

**`features/repositories/repositories.test.tsx`** (16):

| Group | Tests |
|---|---|
| Before repositories | not connected → "Connect GitHub first"; not installed → "Install the DevPulse app"; never synced → "No repositories yet" + "Not synced yet" |
| List | details and badges and "Synced 5 minutes ago"; search as you type updates the URL (`?q=back`); the search box follows Back; "No repositories match"; filter (with `aria-pressed`) and sort by recent push; pagination (Page 1 of 2 → Next → `?page=2`); the **optimistic switch** flips instantly and saves; a **failed save** snaps back with the error |
| Sync | Sync now → "Syncing…" (button disabled) → "Repositories synced" → the new repo appears; second click → the once-a-minute message |
| Detail | shows details and toggles; reachable from the list; 404 → "Repository not found" |

`AppLayout.test.tsx` now navigates to *Reviews* (Repositories is a real page now).

**Totals:** 62 frontend tests.

---

## 5. Verified with the real installation

- GitHub's API (queried directly with an app JWT) reported **2 repositories** granted: `Sanket-0703/drumkit` and `Sanket-0703/devpulse-ai-backend`.
- After **Sync now** in the browser:
  - the account was `SUCCEEDED` with no error;
  - both repositories were stored with language, default branch and last push;
  - AI review was on for `devpulse-ai-backend`;
  - the installation token was cached in Redis **encrypted** (`v1:…`) with about 54 minutes left.

---

## 6. Explain it in an interview

**How does your backend access a user's repositories?**
As the GitHub App, not as the user. It signs a short-lived JWT with the app's private key (RS256), exchanges it for a one-hour installation token, and lists `/installation/repositories`, which returns exactly the repositories the user granted. The token is cached encrypted in Redis until shortly before it expires.

**Why run the sync in the background, and how do you prevent double syncs?**
It's several network calls that can take seconds, so the request just starts it and returns 202. The client polls a status endpoint. A Redis `SET NX` lock with an expiry ensures one sync per account at a time (and frees itself if a sync dies), and a second `SET NX` key enforces "once a minute" for manual syncs, answered with 429 and `Retry-After`. Interrupted syncs are marked failed on startup.

**Why virtual threads?**
The work is I/O-bound: mostly waiting on GitHub. Virtual threads make one-thread-per-task cheap and don't pin an OS thread while blocked on the network.

**How do you keep the local copy consistent with GitHub?**
Reconciliation by GitHub id: upsert everything GitHub returns, delete what it no longer returns, and never touch user-owned fields such as the review flag. Network calls happen outside the transaction, and the apply step is one short transaction.

**How is data isolated between users?**
Every query includes `githubAccount.user.id = currentUser` (via a JPA Specification or a derived query). A foreign id returns the same 404 as a missing one, so ids can't be probed. The uniqueness constraint is per account, so two users can hold the same repository with independent settings.

**Tell me about a bug your tests caught.**
Sorting by "most recently pushed, never-pushed last" returned a 500: Spring Data's Criteria translation doesn't support null precedence. The integration test exposed it, and I moved the ordering into the specification (with a CASE expression), skipping it for the count query. Another one: the test client silently retried a 429 after the `Retry-After` delay, which hid the response; switching that test to the JDK HttpClient made it explicit.

**What does optimistic UI mean in your app?**
Toggling AI review updates every cached list and the detail immediately, before the server answers. In-flight refetches are cancelled so they can't overwrite it, and the change is reverted with an error toast if the server rejects it. Afterwards the lists refetch so filtered views stay correct.

---

## 7. Files in this level

**Backend, new:** `V6__repositories_and_sync.sql` · `entity/SyncStatus` · `security/GitHubAppJwtSigner` · `config/BackgroundConfig` · `service/GitHubInstallationTokenService` + impl · `service/RepositorySyncService` + impl · `service/RepositoryService` + impl · `dto/PageResponse` · `dto/github/`: `RepositoryResponse`, `ReviewSettingRequest`, `SyncStatusResponse`, `GitHubInstallationTokenResponse`, `GitHubInstallationRepositoriesResponse` · `exception/TooManyRequestsException` · tests `GitHubAppJwtSignerTest`, `RepositoryIntegrationTest`

**Backend, changed:** `entity/GitHubRepository` · `entity/GitHubAccount` · `controller/GitHubRepositoryController` (rewritten) · `client/GitHubApiClient(+Impl)` · `dto/github/GitHubRepositoryResponse` · `repository/GitHubRepositoryRepository` · `repository/GitHubAccountRepository` · `service/RedisService(+Impl)` · `service/GitHubCacheService(+Impl)` · `service/impl/GitHubConnectionServiceImpl` · `exception/GlobalExceptionHandler` · `common/RedisKeys` · test `BackendApplicationIntegrationTest`

**Backend, deleted:** `service/GitHubRepositoryService(+Impl)` · `dto/github/GitHubRepositoryCacheDto`

**Frontend, new:** `features/repositories/{api,hooks,components}` · `features/repositories/pages/{RepositoriesPage,RepositoryDetailPage}` · `hooks/useDebouncedCallback.ts` · `lib/time.ts` · `components/ui/switch.tsx` · `test/repositoriesMock.ts` · `features/repositories/repositories.test.tsx`

**Frontend, changed:** `app/routes.tsx` · `app/AppProviders.tsx` (Query Devtools) · `lib/api/schema.d.ts` (regenerated) · `test/{authMock,server,setup}.ts` · `components/layout/AppLayout.test.tsx` · `package.json` (`@tanstack/react-query-devtools`)

**Docs:** `docs/levels/LEVEL_5.md` · `BUILD_PLAN.md` · `IDE_GUIDE.md` (Level 5)
