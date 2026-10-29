# Level 6 — Pull requests

**Goal:** bring each repository's pull requests into DevPulse, browse them by state, and read any pull request's changed files as a proper diff. The diff view, with its old and new line numbers, is where AI findings will appear in Level 7.

**What you can do at the end of Level 6:**
- On a **repository page**:
  - press **Sync** to fetch its pull requests from GitHub; the first sync fetches up to 1,000, later ones only what changed;
  - browse them: **All / Open / Merged / Closed**, search by title, author or `#number`, page through long lists;
  - each row shows the state icon (green open, grey draft, purple merged, red closed), number, author with avatar, last activity and `branch → main`.
- On a **pull request page**:
  - title, state, author, "merged 3 days ago", `source → target` branches, a link to GitHub, and the description;
  - **Files changed**: every file with its status, `+additions −deletions`, and a two-column line-numbered diff;
  - files fold open and closed, and big files start folded;
  - binary files and renames get a plain explanation instead of a diff.

> Builds on [LEVEL_5.md](LEVEL_5.md) (installation tokens, repository sync, ownership checks).

---

## 1. The big ideas

### What's stored, and what's fetched live

| Data | Where it lives | Why |
|---|---|---|
| Pull requests (title, state, branches, author, head commit…) | **PostgreSQL**, via a sync | Lists need filtering, search and paging, which a database does well. GitHub's API can't filter "merged" or search titles cheaply. |
| Changed files + diffs | **Fetched from GitHub** when you open a PR, **cached in Redis** | Diffs can be megabytes. Storing every diff of every PR would bloat the database for data that is rarely read. |

### Incremental sync

GitHub can list a repository's pull requests **sorted by last update, newest first**. That makes "what changed since last time" cheap:

```
GET /repositories/{id}/pulls?state=all&sort=updated&direction=desc&per_page=100&page=1

  #12 updated 10:05   ← newer than the last sync: save
  #9  updated 09:58   ← save
  #4  updated yesterday, before (last sync − 5 min)  ← STOP: everything after is older
```

- **First sync** (`pullRequestsSyncedAt` is null): read pages until a page has fewer than 100 items, or until 10 pages (1,000 PRs) are read.
- **Later syncs** stop at the first pull request older than the previous sync. Usually that's **one request**.
- The cutoff is **5 minutes before** the last sync, to absorb any clock difference between our server and GitHub.
- The new `pullRequestsSyncedAt` is the time the sync **started**, not when it ended. Anything GitHub updates while we're fetching is newer than that, so the next sync picks it up.
- A PR updated during the sync can move between pages and appear twice. The fetched PRs are kept in a map keyed by number, so it is saved once.

### A diff cache keyed by commit

```
cache:github:pr-files:{GitHub repository id}:{PR number}:{head commit sha}
```

- A diff only changes when someone pushes a commit, and a push changes the head sha. So a cache entry for one sha can never be stale. When the sync records a new sha, the next request reads a new key. The 1-hour TTL is only there to free memory.
- The key uses **GitHub's** repository id. Two DevPulse users who share an organization repository share the cache. That's safe because each request passes the ownership check *before* the cache is read.
- Very large responses (over 1,000,000 characters) are served but **not cached**, so a single huge PR can't fill Redis.

### Synchronous sync, this time

Level 5 synced repositories in the background with polling. Pull requests sync **inside the request**:
- an incremental sync is usually one GitHub call, and even a first sync is at most 10;
- the user is looking at that repository and wants the result now.

A Redis lock (`SET NX`, 2-minute expiry) still prevents two syncs of the same repository at once; the second gets **409**. When webhooks arrive in Level 9, GitHub will push changes and manual syncs will rarely be needed.

### When GitHub fails

Before Level 6, a GitHub failure outside the OAuth callback ended up as a generic 500. Now `GlobalExceptionHandler` turns `GitHubIntegrationException` into **502 Bad Gateway**: the request was fine, the server it depends on wasn't. The message depends on the reason:
- **GitHub said 404:** the app may have lost access to the repository, so sync your repositories.
- **App not configured:** the server is missing its app id or private key.
- **Anything else:** try again in a moment.

---

## 2. Database: `V7__pull_requests.sql`

The `pull_requests` table existed since V1 but nothing filled it. V7 makes it fit for real data:

1. **`DELETE FROM pull_requests`** first. The table is empty in practice (V5 emptied it and nothing wrote to it since), and pull requests are re-created by the next sync. Starting clean lets the new `NOT NULL` columns be added without inventing values.
2. **Drops the global `UNIQUE (github_id)`** (V1's auto-named `ukhjvu0eci20kg12cepliigk6q4`). Same reason as for repositories in V6: two users can see the same organization PR. The real key is **(repository row, number)**, and `uk_pull_request_repository_number` already enforces it.
3. **State:**
   - the `CHECK` constraint now allows `OPEN`, `CLOSED`, `MERGED`;
   - the redundant `merged` boolean is dropped, because "merged" is now a state;
   - the column becomes `VARCHAR(20)`.
4. **`description` becomes `TEXT`**. GitHub allows 65,536 characters; the old limit was 10,000.
5. **Timestamps become `TIMESTAMPTZ`** (UTC), like every other table since V4. `opened_at` becomes `NOT NULL`.
6. **New columns:**
   - `github_updated_at`: the list order and the incremental-sync cutoff;
   - `draft`;
   - `head_sha`: the commit the PR points at. Reviews in Level 7 are per commit;
   - `author_avatar_url`.
7. **The foreign key gets `ON DELETE CASCADE`**. Deleting a repository row deletes its pull requests even when the delete doesn't go through JPA.
8. **Two indexes** that match the list query exactly:
   - `(github_repository_id, github_updated_at DESC)` for "All";
   - `(github_repository_id, state, github_updated_at DESC)` for a state filter.
9. **`github_repositories.pull_requests_synced_at`**: null means never synced. The frontend uses it to show "Synced 2 minutes ago" or the first-sync screen.

---

## 3. Backend, class by class

### 3.1 Entities

**`entity/PullRequest`**: rewritten to match V7.
- `Instant` timestamps.
- `state` (`PullRequestState`) and `draft`.
- `headSha`, `authorUsername` and `authorAvatarUrl`.
- `githubUpdatedAt` (GitHub's time) next to the inherited `updatedAt` (when *our* row changed).
- `description` is `columnDefinition = "text"`.
- `@ManyToOne(fetch = LAZY) githubRepository`.

**`entity/PullRequestState`**: `OPEN`, `CLOSED`, `MERGED`. The JavaDoc explains that GitHub itself only has open and closed.

**`entity/GitHubRepository`**: new `pullRequestsSyncedAt`.

### 3.2 GitHub API: `client/GitHubApiClient` + `impl/GitHubApiClientImpl`

- **`listPullRequests(installationToken, githubRepositoryId, page, perPage)`** calls `GET /repositories/{id}/pulls?state=all&sort=updated&direction=desc`.
  - It goes **by repository id**, not `owner/name`: ids survive renames and transfers.
  - It authenticates with the **installation token** from Level 5.
- **`listPullRequestFiles(installationToken, githubRepositoryId, number, page, perPage)`** calls `GET /repositories/{id}/pulls/{number}/files`.
- **`call(...)`**, the one place that turns HTTP failures into our exception, now tells a GitHub **404** apart (`reason = "github_not_found"`) from other failures (`"github_error"`).
- **Removed:** `getRepositories(account, …)` and `getPullRequests(account, …)`, the old user-token versions.

**DTOs for GitHub's JSON** (`dto/github/`), both with `@JsonIgnoreProperties(ignoreUnknown = true)` so new GitHub fields can't break parsing:
- **`GitHubPullRequestResponse`** is rewritten:
  - `Instant` dates (GitHub sends `2026-10-24T19:42:54Z`, which `LocalDateTime` can't parse);
  - `draft`, `updated_at`, `user.avatar_url` and `head.sha`.
- **`GitHubPullRequestFileResponse`** (record): `filename`, `status`, `additions`, `deletions`, `changes`, `patch` (the unified diff), `previous_filename` and `blob_url`.

### 3.3 `repository/PullRequestRepository`

- Extends `JpaSpecificationExecutor` for the filtered list.
- **`findByGithubRepositoryIdAndNumberIn(repoId, numbers)`**: the rows a sync is about to update, in **one query** instead of one per PR.
- **`countByGithubRepositoryId`**: the total returned by a sync.
- **`findOwned(userId, repositoryId, number)`** is a JPQL query with `join fetch`:
  - it loads the PR, its repository and the account in one query;
  - it only matches if `account.user.id = :userId`. Someone else's PR looks exactly like a missing one (404).
  - The `join fetch` matters for the files endpoint, which runs without a transaction (see 3.5).

### 3.4 `service/PullRequestSyncService` + `impl/PullRequestSyncServiceImpl`

The constants are the design decisions:
- `PAGE_SIZE = 100`;
- `MAX_PAGES = 10`;
- `CUTOFF_OVERLAP = 5 min`;
- `SYNC_LOCK_TTL = 2 min`.

**`sync(userId, repositoryId)`**
1. Reads what it needs in a short transaction (`TransactionTemplate`), as a small `Target` record:
   - the repository's GitHub id;
   - the account's installation id;
   - `pullRequestsSyncedAt`.

   Someone else's repository → `ResourceNotFoundException` (404).
2. No installation id → `ConflictException` (409).
3. Takes the lock `lock:github:pr-sync:{repositoryId}` with `setIfAbsent`. If it's held → 409 "already syncing".
4. Records `startedAt` and computes the cutoff (null on the first sync).
5. **`fetchChanged`**, outside any transaction (no database connection is held while waiting on GitHub).
6. One short transaction:
   - re-loads the repository (it may have been deleted meanwhile → 404);
   - runs **`apply`**;
   - sets `pullRequestsSyncedAt = startedAt`;
   - returns `PullRequestSyncResponse(updated, total, syncedAt)`.
7. `finally`: releases the lock, even when GitHub failed.

**`fetchChanged(target, cutoff)`**
- Gets the cached installation token.
- Reads pages and stops at the first PR older than the cutoff, at a short page, or at `MAX_PAGES`.
- Collects into a `LinkedHashMap` keyed by number (`putIfAbsent`), which removes duplicates and keeps newest-first order.
- On a `GitHubIntegrationException` it **invalidates the cached installation token** (it may have been revoked) and rethrows.

**`apply(repository, remote, now)`**
- Loads the existing rows for those numbers in one query, into a map by number.
- For each PR from GitHub: updates the existing row, or creates a new `PullRequest` and sets its repository.
- It deliberately does **not** call `repository.addPullRequest(...)`. Touching that lazy collection would load *every* PR of the repository into memory.

**`copy(source, target, now)`** (static, so the Level 9 webhook handler can reuse it) maps GitHub's fields onto the row:
- the title is truncated to 500 characters, and a blank body becomes `null`;
- a deleted GitHub user is shown as `ghost`, GitHub's own name for them;
- a missing `updated_at` falls back to `created_at`.

**`stateOf(source)`**
- `merged_at` is set → `MERGED`;
- otherwise `"open"` → `OPEN`;
- otherwise `CLOSED`.

### 3.5 `service/PullRequestService` + `impl/PullRequestServiceImpl`

**`list(userId, repositoryId, state, query, page, size)`** (`@Transactional(readOnly = true)`)
- Checks the repository is the user's, with Level 5's `findByIdAndGithubAccountUserId`, else 404.
- Builds a `Specification` from three pieces:
  - **`inRepository(id)`**;
  - **`stateIs(state)`**, which returns `null` ("no condition") when state is null, i.e. All;
  - **`matches(query)`**: title **or** author contains the text (case-insensitive, via `SqlLike`). If the query is a number (`42` or `#42`), it also matches `number = 42`.
- Sorts by `githubUpdatedAt desc, number desc`. The second key makes the order stable when two PRs have the same timestamp.
- The size is clamped to 1–100.

**`get(userId, repositoryId, number)`**: `findOwned` → `PullRequestDetailResponse`, or 404.

**`files(userId, repositoryId, number)`** is **not `@Transactional`**: no database connection is held while waiting on GitHub.
1. `findOwned` (one query, everything fetched).
2. Builds the cache key from GitHub's repository id, the number and `headSha`.
3. **`readCache`** returns the cached response if there is one. An unreadable entry (for example written by an older version with a different shape) is logged and treated as a miss.
4. No installation → 409.
5. Fetches up to `MAX_FILE_PAGES = 3` pages of 100. A full third page sets `truncated = true`, so the UI can say "more files than shown".
6. On a GitHub failure it invalidates the installation token and rethrows (→ 502).
7. **`writeCache`** stores the JSON for 1 hour if it's at most `MAX_CACHED_CHARS`.

**`parseNumber(query)`**:
- accepts `42` and `#42`, up to 9 digits so it always fits an `int`;
- returns null for anything else (`fix 42`, `#`).

### 3.6 `common/SqlLike`: a small shared helper

`containsPattern(input)` lower-cases the input, escapes `\`, `%` and `_`, and wraps it in `%…%`; `ESCAPE` is the escape character to pass to `cb.like(...)`. This used to be a private method in `RepositoryServiceImpl`. Now both services use it, so a search for `50%` or `my_repo` is literal everywhere.

### 3.7 DTOs (what the frontend receives)

- **`PullRequestResponse`**: a list row.
  - id, number, title, state, draft;
  - `authorLogin`, `authorAvatarUrl`;
  - `sourceBranch`, `targetBranch`, `headSha`, `htmlUrl`;
  - `openedAt`, `updatedAt` (GitHub's activity time), `closedAt`, `mergedAt`.
  - **No description**: twenty descriptions of up to 65 KB each would make list pages heavy.
- **`PullRequestDetailResponse`**: `{ pullRequest, body }`. It reuses the list shape and adds the description.
- **`PullRequestFilesResponse`**: `{ headSha, files: ChangedFile[], truncated }`.
  - `ChangedFile` is `filename`, `previousFilename`, `status`, `additions`, `deletions`, `patch` (null for binary or too-large diffs) and `blobUrl`.
  - It's named `ChangedFile` and not `File` because OpenAPI schema names are global: a schema called `File` would clash sooner or later.
- **`PullRequestSyncResponse`**: `{ updated, total, syncedAt }`.
- **`RepositoryResponse`** gains `pullRequestsSyncedAt`.

All required fields carry `@Schema(requiredMode = REQUIRED)`, so the generated TypeScript types are not all optional.

### 3.8 `controller/PullRequestController`

Rewritten from scratch. The old controller had `GET /api/github/pull-requests/{id}` with **no ownership check** (any signed-in user could read any PR by id) and a `DELETE`.

Base path: `/api/github/repositories/{repositoryId}/pull-requests`, addressed like GitHub (repository + `#number`):

| Method and path | What it does |
|---|---|
| `GET /` | the list (`state`, `q`, `page`, `size`) |
| `POST /sync` | sync now, returns the result (200) |
| `GET /{number}` | detail with description |
| `GET /{number}/files` | changed files with diffs |

Every handler takes `@AuthenticationPrincipal` and passes the user id down. Ownership is decided in the services.

### 3.9 Other changes

- **`GlobalExceptionHandler.handleGitHub`**: `GitHubIntegrationException` → 502 with a reason-specific message (see section 1).
- **`application.properties`: `spring.jpa.open-in-view=false`**.
  - Spring Boot's default keeps a database session open for the *whole* web request, so lazy loading works in controllers. The cost is a connection held for the request's full length, including while it waits on GitHub.
  - With it off, services must load what they need inside their transactions. That's exactly what `findOwned`'s `join fetch` does.
  - The full test suite confirmed nothing relied on the old behaviour.
- **`common/RedisKeys`**: `GITHUB_PR_SYNC_LOCK_PREFIX` and `GITHUB_PR_FILES_PREFIX` replace the unused `GITHUB_PULL_REQUESTS_PREFIX`.
- **Deleted:** `service/GitHubCacheService` + impl (cached entities as untyped JSON, never read correctly), and the old `PullRequestService` + impl and controller.

### 3.10 Backend tests

**`GitHubAppIntegrationTest`** (new, abstract): the fake GitHub moved out of `RepositoryIntegrationTest`, so both test classes share it:
- the WireMock server and the RSA key;
- `Identity`, `stubIdentity`, `repo(...)`;
- `signInWithGitHub`, `awaitSync`, `registerAndLogin`, `bearer(...)`.

Because the `@DynamicPropertySource` is declared once on the base class, both subclasses get the **same Spring context** (one startup, not two).

**`PullRequestIntegrationTest`** (6 tests): a real sign-in with GitHub, a real repository sync, then:
- **sync + list**:
  - states including merged and draft, newest first;
  - the state filter;
  - search by title, author (case-insensitive) and `#2`;
  - paging;
  - `pullRequestsSyncedAt` set on the repository.
- **incremental sync**: the second sync gets "#2 updated now, #1 long ago", and only #2 is updated (`updated = 1`). The test checks the request used `state=all&sort=updated&direction=desc`.
- **every page on the first sync**: 100 PRs on page 1 and 1 on page 2 → 101 stored.
- **detail + files**:
  - the description;
  - a patch with newlines, a binary file without a patch, a rename with `previousFilename`;
  - asking twice reaches GitHub **once** (Redis cache).
- **GitHub failures**: a 500 from GitHub → 502 "GitHub didn't respond…"; a 404 from GitHub → 502 "GitHub couldn't find this…".
- **ownership**: another user gets 404 on the list, sync, detail and files; a missing number is 404 for the owner too.

**`PullRequestSyncServiceImplTest`** (6, Mockito):
- someone else's repository is a 404 and touches neither Redis nor GitHub;
- no installation → 409;
- the lock is held → 409 with no GitHub call;
- a GitHub failure invalidates the token **and** releases the lock;
- the `stateOf` mapping;
- `copy` with a deleted author, a blank body, a 600-character title and a missing `updated_at`.

**`PullRequestServiceImplTest`** (4, Mockito):
- an unreadable cache entry is refetched and replaced;
- 3 full pages → 300 files and `truncated = true`;
- huge diffs are served but not cached;
- `parseNumber` cases.

**Also:** `BackendApplicationIntegrationTest` now expects migrations 1–7.

**Totals:** 131 backend tests, all green; instruction coverage **89.6%**.

---

## 4. Frontend, file by file

### 4.1 `features/pull-requests/api.ts`

- Types come from the generated `schema.d.ts`: `PullRequest`, `PullRequestPage`, `PullRequestDetail`, `PullRequestFiles`, `ChangedFile`, `PullRequestSync`, and `PullRequestState` (derived from `PullRequest['state']`).
- `StateFilter` is `'all' | PullRequestState`; `PullRequestListParams` is `{ q, state, page }` with `PAGE_SIZE = 20`.
- **`pullRequestKeys`**: every key starts with `['pull-requests', repositoryId]`, so one `invalidateQueries` refreshes a repository's list, details and files after a sync.
- **Functions:**
  - `listPullRequests` sends `state` only when it isn't `all`, and `q` only when non-empty;
  - `syncPullRequests`, `getPullRequest` and `getPullRequestFiles` are thin typed wrappers around `openapi-fetch` + `unwrap`.

### 4.2 `features/pull-requests/hooks.ts`

- **`usePullRequests(repositoryId, params)`** uses `keepPreviousData`: the current page stays visible (dimmed) while the next one loads.
- **`usePullRequest`** sets `retry: false`, so a 404 shows "not found" immediately.
- **`usePullRequestFiles`** sets `staleTime: 5 min`. A diff only changes with a new commit, so refocusing the tab doesn't refetch it.
- **`useSyncPullRequests(repositoryId)`** is a mutation. On success it:
  - writes `pullRequestsSyncedAt` straight into the cached repository, so "Synced just now" appears without a refetch;
  - invalidates the repository's pull-request queries;
  - toasts "Synced 2 pull requests" or "Pull requests are up to date".

  On error it toasts the server's message (for example 409 "already syncing").

### 4.3 `features/pull-requests/diff.ts`: `parsePatch`

GitHub's `patch` is a unified diff:

```
@@ -10,4 +10,5 @@ function login() {     hunk header: old start, new start
 const a = 1                              context  (old 10, new 10)
-const b = 2                              removed  (old 11)
+const b = 3                              added    (new 11)
+const c = 4                              added    (new 12)
 return a                                 context  (old 12, new 13)
\ No newline at end of file               a note about the line above
```

`parsePatch` walks the lines and keeps two counters:
- a **hunk header** resets both counters to its start numbers;
- `+` advances only the new counter, `-` only the old one, and context advances both;
- `\` lines become notes.

Two details:
- an empty line inside a hunk counts as context (its leading space can get lost);
- a trailing newline doesn't create an extra line, and anything before the first hunk is ignored.

The result is a typed list (`DiffLine` is a discriminated union on `kind`), so TypeScript knows an `add` line has `newLine` but no `oldLine`. These line numbers are what Level 7's inline findings will attach to.

### 4.4 `features/pull-requests/DiffView.tsx`

- A real `<table>`, so screen readers can navigate it:
  - a `caption` "Changes in {file}" and a screen-reader-only header row;
  - an "Added:" or "Removed:" prefix for screen readers on changed lines, since colour alone carries no meaning.
- Each row shows old line, new line, then a `+`/`-` marker and the code. The marker is `aria-hidden` and `select-none`, so copying code doesn't copy markers.
- Long lines scroll sideways (`whitespace-pre` in an `overflow-x-auto` box) instead of wrapping, like GitHub.
- Background tints for added, removed and hunk rows work in light and dark mode.
- `useMemo` parses the patch once per patch, not on every render.

### 4.5 `features/pull-requests/components.tsx`

- **`PullRequestStateBadge`**: an icon and label in GitHub's colours (Open, Draft, Merged, Closed), with dark-mode variants. Draft is an open PR with `draft = true`.
- **`PullRequestStateIcon`**: the icon alone for dense list rows, with the label in an `sr-only` span.
- **`AuthorAvatar`**: the GitHub avatar, or the login's first two letters.

### 4.6 `features/pull-requests/PullRequestsSection.tsx`

The "Pull requests" section on the repository page.
- **Header:** "Synced 2 minutes ago" / "Syncing with GitHub…" / "Not synced yet" in an `aria-live` region, plus the **Sync** button with a spinner while it runs.
- **Never synced:** an empty state with "Sync pull requests".
- **`PullRequestBrowser`:**
  - state, search and page live in the **URL** (`?state=MERGED&q=%231&page=2`), with the same `useListParams` pattern as the repositories page, so reload, back/forward and shared links keep the view;
  - search is debounced by 300 ms, and the input re-syncs when the URL changes on its own;
  - it shows the right empty state: nothing matches the filter, or no pull requests at all.
- **`PullRequestRow`:** state icon, title link to `/repositories/{id}/pulls/{number}`, `#number`, author with avatar, "updated 2 hours ago", `branch → main`.

### 4.7 `features/pull-requests/pages/PullRequestDetailPage.tsx`

Route `/repositories/:id/pulls/:number`.
- **Header:**
  - a back link named after the repository;
  - the title with `#number`;
  - the state badge and author;
  - **`summary(pr)`**: "merged 3 days ago", "closed this…" or "opened this…";
  - both branches as badges, and **Open on GitHub**.
- **Description:** shown as plain text with line breaks kept, or "No description provided." Rendering Markdown safely needs a sanitizing renderer, which comes with the review report.
- **`FilesChanged`:**
  - its own query, so the description shows while the diff loads;
  - **`ChangeSummary`**: "3 files · +2 −0";
  - a "more files than shown" alert when `truncated`;
  - a GitHub failure appears as an error alert with **Retry**, and the rest of the page keeps working.
- **`FileDiff`:** a native `<details>`/`<summary>` (keyboard-accessible folding without JavaScript state).
  - The header shows a chevron, the status, `old → new` for renames, and `+a −d`.
  - Files with more than **400** changed lines start folded, so a huge PR doesn't render tens of thousands of rows at once.
  - With no patch, it explains why ("renamed without changes", or binary / too large) and links to the file on GitHub.

### 4.8 Shared pieces moved to `components/common`

`SegmentedControl` and `Pagination` were private to the repositories page; the pull request list needs both. `RepositoriesPage` now uses them too, with no visible change. `Pagination` takes a `noun` ("repositories", "pull requests").

### 4.9 Wiring

- `app/routes.tsx` adds `/repositories/:id/pulls/:number`.
- `RepositoryDetailPage` replaces the "arrives in Level 6" placeholder with `<PullRequestsSection repository={repo} />`.
- `lib/api/schema.d.ts` is regenerated (`npm run api:types`); the old pull-request endpoints disappeared from it.

### 4.10 Frontend tests

**`test/pullRequestsMock.ts`**: an in-memory imitation of the four endpoints.
- Pull requests appear only after a sync, like the real server.
- Helpers: `pullRequest()`, `changedFile()`, `setSyncedPullRequests()`, `setNextPullRequestSync()`, `setPullRequestDetails()`, `failPullRequestFiles()`.
- Reset before every test in `test/setup.ts`, and registered in `test/server.ts`.

**`diff.test.ts`** (4):
- the line numbers in both files;
- the numbering restarts per hunk;
- new files, `\ No newline` notes, blank context lines, a trailing newline;
- text before the first hunk is ignored.

**`pull-requests.test.tsx`** (6):
- the first-sync screen → Sync → rows with Draft/Merged, `#2`, `feature-2 → main`, the toast, "Synced just now";
- the state filter and a `#1` search, both in the URL, then "No pull requests match";
- the "No pull requests yet" state;
- the PR page from a list click:
  - heading, Merged, description, back link, "3 files";
  - the diff rows with line numbers and the screen-reader prefix;
  - the binary-file message with its GitHub link, and the renamed file;
- a GitHub failure while loading files shows the server's message and Retry, with the rest of the page intact;
- a missing PR → "Pull request not found".

One lesson from these tests: with `keepPreviousData`, the *old* results stay on screen until the new ones arrive. A test must wait for the old row to **disappear**, not just for the new one to appear.

**Totals:** 72 frontend tests; lint, formatting and type-check clean.

---

## 5. Verified against real GitHub

Using the app's own identity (app JWT → installation token), GitHub's API reported:
- 3 pull requests on `Sanket-0703/devpulse-ai-backend`:
  - **#3** open draft (`level-5-repositories → main`);
  - **#2** and **#1** merged;
- more than 100 changed files on #1 (a full first page), so the files endpoint really pages, and `Basic Roadmap.jpeg` comes without a patch (binary).

These are exactly the shapes the code and tests rely on: `/repositories/{id}/pulls` by id, `sort=updated`, `draft`, `head.sha`, `merged_at`, and a missing `patch` for binaries.

---

## 6. Explain it in an interview

**How do you keep pull requests in sync without re-downloading everything?**
GitHub lists them by last update, newest first. Each sync stops at the first PR older than the previous sync (minus a 5-minute overlap for clock differences), so a routine sync is one request. The new sync time is when the sync *started*, so anything updated during the fetch is caught next time. Webhooks (Level 9) will push changes as they happen; the sync stays as a fallback.

**Why store pull requests but not diffs?**
Lists need filtering, search and paging, which belong in the database. Diffs are big, read rarely, and change only with a new commit. So they're fetched live and cached in Redis under a key that includes the head commit sha. A new commit means a new key, so the cache can't serve a stale diff.

**What did turning off open-in-view change?**
By default Spring keeps a database session for the whole request, so a request that waits on GitHub also holds a pool connection the entire time. With it off, services load everything inside their transactions (`join fetch` for the files endpoint), and the slow GitHub call runs with no connection held. Under load that's the difference between a pool that drains and one that doesn't.

**How did you fix the old pull request API?**
It returned entities by bare id with no ownership check, an IDOR: any signed-in user could read any pull request. Now everything is nested under the repository, and every query is scoped to the current user. Someone else's id returns the same 404 as a missing one.

**How do you render a diff, and why does the line numbering matter?**
I parse the unified diff myself: the hunk header gives the starting line numbers, and each `+`, `-` or context line advances the new counter, the old counter or both. Every line ends up with its line number in the old and/or new file. That's what GitHub's review comments are anchored to, so Level 7's findings can sit on the right line and Level 9 can post them as inline comments.

**How do you handle a GitHub outage?**
Its failures become our `GitHubIntegrationException`, which the global handler maps to 502 with a readable message. A 404 gets its own explanation (the app probably lost access). The cached installation token is dropped in case it was revoked. The sync lock is released in `finally`. In the UI, only the part that needed GitHub shows an error with Retry.

**What made the integration tests fast?**
I moved the fake GitHub (WireMock plus a generated RSA key) into a shared abstract base class with one `@DynamicPropertySource`. Spring caches application contexts by configuration, so both GitHub test classes reuse one context instead of starting two.

---

## 7. Files in this level

**Backend, new:**
- `db/migration/V7__pull_requests.sql`
- `common/SqlLike`
- `service/PullRequestSyncService` + `impl/PullRequestSyncServiceImpl`
- `dto/github/`: `GitHubPullRequestFileResponse`, `PullRequestResponse`, `PullRequestDetailResponse`, `PullRequestFilesResponse`, `PullRequestSyncResponse`
- tests: `GitHubAppIntegrationTest`, `PullRequestIntegrationTest`, `PullRequestSyncServiceImplTest`, `PullRequestServiceImplTest`

**Backend, rewritten:**
- `entity/PullRequest`
- `controller/PullRequestController`
- `service/PullRequestService` + `impl/PullRequestServiceImpl`
- `repository/PullRequestRepository`
- `dto/github/GitHubPullRequestResponse`

**Backend, changed:**
- `entity/PullRequestState`, `entity/GitHubRepository`
- `dto/github/RepositoryResponse`
- `client/GitHubApiClient(+Impl)`
- `service/impl/RepositoryServiceImpl` (uses `SqlLike`)
- `exception/GlobalExceptionHandler`, `common/RedisKeys`
- `application.properties` (open-in-view off)
- tests: `RepositoryIntegrationTest` (uses the new base class), `BackendApplicationIntegrationTest`

**Backend, deleted:** `service/GitHubCacheService` + `impl/GitHubCacheServiceImpl`

**Frontend, new:**
- `features/pull-requests/`: `api.ts`, `hooks.ts`, `diff.ts`, `components.tsx`, `DiffView.tsx`, `PullRequestsSection.tsx`, `pages/PullRequestDetailPage.tsx`
- `components/common/SegmentedControl.tsx`, `components/common/Pagination.tsx`
- `test/pullRequestsMock.ts`
- tests: `features/pull-requests/diff.test.ts`, `pull-requests.test.tsx`

**Frontend, changed:**
- `app/routes.tsx`
- `features/repositories/pages/RepositoryDetailPage.tsx`, `RepositoriesPage.tsx`
- `lib/api/schema.d.ts` (regenerated)
- `test/{server,setup}.ts`

**Docs:**
- `docs/levels/LEVEL_6.md`
- `BUILD_PLAN.md`
- `IDE_GUIDE.md` (Level 6)
- `http/devpulse.http` (repository and pull request requests)
