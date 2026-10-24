# Task Backlog

Conventions
- **Priority:** P0 = blocks the milestone exit · P1 = needed for the milestone, not blocking · P2 = nice to have / can slip.
- **Type:** BE = backend · FE = frontend · INF = infrastructure/DevOps · FS = full-stack task touching both.
- **Effort:** S ≤ 2 h · M ≈ half day · L ≈ 1 day · XL = split before starting. These are estimates, not commitments.
- One task ≈ one reviewable PR. Every BE task that changes the schema ships a Flyway migration. Every FE task ships loading, error, and empty states.
- "Verify" lists the checks that must pass before the task is marked done. Nothing has been run yet.

---

## Part A: Completed work (code review evidence only; not runtime-verified)

| ID | Item | Evidence | Caveat |
|---|---|---|---|
| C-01 | Maven project, Spring Boot 3.5.6, Java 21, layered packages | `pom.xml` | Compile not yet verified |
| C-02 | Registration with BCrypt + validation | `UserServiceImpl.register`, `RegisterRequest` | Returns plain text |
| C-03 | Login issuing JWT with `jti` and expiry | `JwtService.generateToken` | Secret leaked → rotate (M0-01) |
| C-04 | Logout via Redis `jti` blacklist | `AuthController.logout`, `JwtBlacklistServiceImpl` | Crashes on malformed token (M1-03) |
| C-05 | Profile read/update, change password | `ProfileController`, `UserServiceImpl` | 500 on missing user (M1-04) |
| C-06 | Role-based method security | `SecurityConfig` `@EnableMethodSecurity`, `AdminController` | — |
| C-07 | GitHub OAuth state (HMAC, Redis, single-use, 10 min) | `GitHubOAuthStateServiceImpl` | — |
| C-08 | GitHub API client: user, repos, PRs (paged) | `GitHubApiClientImpl` | No timeouts or rate-limit handling |
| C-09 | Repo + PR upsert synchronization | `GitHubRepositoryServiceImpl`, `PullRequestServiceImpl` | Sync in request thread; PR cache bug |
| C-10 | Redis wrapper + repository cache | `RedisServiceImpl`, `GitHubCacheServiceImpl` | — |
| C-11 | AI provider abstraction, mock + OpenAI, JSON prompt | `AIProviderConfig`, `prompts/code-review.st` | Snippet-only, unpersisted |
| C-12 | Flyway V1 baseline for 4 tables | `V1__baseline.sql` | `ddl-auto=update` locally |
| C-13 | Multi-stage non-root Dockerfile | `Dockerfile` | Bundles the 90 MB model |

---

## Part B: Remaining work

### M0: Repository recovery & local development

#### M0-01 · P0 · INF/BE · S — Remove literal secrets and rotate the JWT secret
- **Outcome:** no secret values in tracked files. App reads `JWT_SECRET`, `DB_PASSWORD`, etc. from the environment with no fallback defaults.
- **Files:** `src/main/resources/application.properties`, `application-docker.properties`, `.env.example`.
- **Depends on:** D-01 decision (whether to rewrite history). Blocks M0-04.
- **Acceptance:** `git grep -nE '^(jwt\.secret|spring\.datasource\.password|spring\.ai\.openai\.api-key)=[^$]'` returns nothing. A new JWT secret (≥ 256-bit) is used locally. `.env.example` lists every variable with placeholder values only.
- **Verify:** start the app with `.env` → login works. Start without `JWT_SECRET` → fails fast with a clear message. Run gitleaks (or similar) on `HEAD`.

#### M0-02 · P0 · BE · S — Confirm compile on JDK 21
- **Outcome:** `./mvnw -DskipTests compile` succeeds on this Mac. Any compile blockers are fixed minimally (e.g. the `S3Service` package/dir mismatch, if it breaks the build).
- **Depends on:** MAC_SETUP §2.1–2.2.
- **Acceptance:** build log saved in the PR description. No behaviour changes.
- **Verify:** `./mvnw -DskipTests compile`.

#### M0-03 · P0 · INF · M — Local dev compose with pgvector and Redis
- **Outcome:** `compose.dev.yaml` with `pgvector/pgvector:pg17` (port 5433, named volume, healthcheck) and `redis:7` (healthcheck). Backend runs from the IDE/`mvnw` against it.
- **Files:** new `compose.dev.yaml`; keep `compose.yaml` for the containerized backend.
- **Acceptance:** `docker compose -f compose.dev.yaml up -d` → both healthy. `CREATE EXTENSION vector` succeeds.
- **Verify:** `psql -h localhost -p 5433 -U devpulse -c "select extversion from pg_extension where extname='vector'"`, `redis-cli ping`.

#### M0-04 · P0 · INF/BE · M — Fix profiles and environment mapping
- **Outcome:** profiles `local` (default for dev), `docker`, `prod`. Datasource URL is built from `DB_HOST/DB_PORT/DB_NAME`. Redis uses `REDIS_HOST/REDIS_PORT` (fixes the `devpulse-redis` vs `redis` mismatch). Bucket/region come from env. `show-sql` is off by default.
- **Files:** `application*.properties`, `compose.yaml`, `S3Config.java`, `.env.example`.
- **Depends on:** M0-01, M0-03.
- **Acceptance:** no hostnames or bucket names hard-coded in properties. `docker compose up` (backend + redis) connects to Redis.
- **Verify:** `/actuator/health` shows `db` and `redis` UP in both the local and docker runs.

#### M0-05 · P1 · INF/BE · M — Take the ONNX model out of Git and the image
- **Outcome:** the model and tokenizer are loaded from a configurable URI (default: the Spring AI/Hugging Face URL with on-disk cache, or a path mounted in Docker). `src/main/resources/embeddings/` is removed and gitignored.
- **Depends on:** D-06 (embedding strategy), D-01 (history rewrite to purge the blob).
- **Acceptance:** the repo working tree has no file over 5 MB. The image shrinks by about 90 MB. Embedding still yields 384-dim vectors.
- **Verify:** an integration test embeds a string and asserts `length == 384`. `docker image ls` size before and after.

#### M0-06 · P1 · BE · S — Make Flyway the schema authority
- **Outcome:** `ddl-auto=validate` in all profiles. `V2__enable_pgvector.sql` creates the `vector` extension. Spring AI `initialize-schema=false` once M6-01 lands (until then, keep it true and document why).
- **Acceptance:** an empty DB migrates cleanly and Hibernate validation passes.
- **Verify:** M0-07 integration test boots against an empty Testcontainers DB.

#### M0-07 · P0 · BE · M — Test harness: Testcontainers integration base
- **Outcome:** `spring-boot-testcontainers` + `testcontainers-postgresql` (pgvector image) + a Redis container. An `AbstractIntegrationTest` with `@ServiceConnection`. A `test` profile with `ai.provider=mock` and the embedding model stubbed or cached. `contextLoads` replaced by a smoke test (health UP + register/login round trip).
- **Depends on:** Docker installed, M0-04.
- **Acceptance:** `./mvnw verify` passes from a clean clone with only Docker running.
- **Verify:** `./mvnw verify` locally (output pasted in the PR).

#### M0-08 · P1 · INF · S — GitHub Actions CI
- **Note:** tests boot the full app, which downloads the ~87 MB embedding model on first start. Cache `~/.devpulse/model-cache` in CI (actions/cache keyed on the pinned revision).
- **Outcome:** `.github/workflows/ci.yml` runs on PRs: JDK 21 setup, Maven cache, `./mvnw verify`, gitleaks secret scan. A frontend job is added in M2-03.
- **Acceptance:** CI green on a PR. Branch protection on `main` requires it (you configure this in GitHub settings).
- **Verify:** the PR shows a passing check.

### M1: Backend stabilization & security baseline

#### M1-01 · P0 · BE · M — Response DTOs for repositories and pull requests
- **Outcome:** `RepositoryResponse`, `PullRequestSummaryResponse`, `PullRequestDetailResponse` (records) plus mappers. No controller returns an entity.
- **Files:** `GitHubRepositoryController`, `PullRequestController`, new `dto/github/*`, new `mapper/` package (or static factory methods).
- **Acceptance:** `grep -rn "ResponseEntity<GitHubRepository>\|ResponseEntity<PullRequest>\|List<PullRequest>>" controller/` is empty. No response contains `accessToken` or `refreshToken`.
- **Verify:** MockMvc tests assert the JSON shape and the absence of token fields.

#### M1-02 · P0 · BE · M — Ownership enforcement (fix IDOR)
- **Outcome:** every repo/PR read or delete is resolved through the authenticated user's GitHub account (e.g. `findByIdAndGithubAccountUserId`). Unknown or foreign IDs → 404.
- **Depends on:** M1-01.
- **Acceptance:** user B gets 404 for every endpoint using user A's IDs. The `/account/{accountId}` endpoint is replaced by a "mine" endpoint (M3-05) or locked down.
- **Verify:** integration tests with two users.

#### M1-03 · P0 · BE · S — Robust JWT filter + JSON 401/403
- **Outcome:** malformed, expired, bad-signature, or blacklisted tokens → request continues unauthenticated → a JSON 401 from a custom `AuthenticationEntryPoint`. Add a JSON `AccessDeniedHandler`. Logout tolerates invalid tokens.
- **Files:** `JwtAuthenticationFilter`, `SecurityConfig`, `AuthController.logout`.
- **Acceptance:** each bad-token variant returns `401 {"status":401,...}`, never 500.
- **Verify:** unit tests for `JwtService`; MockMvc tests for each token variant.

#### M1-04 · P1 · BE · S — Complete the error contract
- **Outcome:** handlers for `AIServiceException` (502), `AccessDeniedException` (403), `HttpMessageNotReadable`/`MethodArgumentTypeMismatch`/`ConstraintViolation` (400), and a catch-all (500, no stack trace in the body). `RuntimeException("User not found")` is replaced with `ResourceNotFoundException`. `ErrorResponse` gains `path` and optional `errors[]`.
- **Acceptance:** documented error shape used by every endpoint.
- **Verify:** MockMvc tests per handler.

#### M1-05 · P1 · BE · S — Fix the PR cache serialization
- **Outcome:** cache stores `List<PullRequestSummaryResponse>` with typed deserialization. The `JsonNode` → `List<PullRequest>` cast is removed.
- **Files:** `GitHubCacheService(Impl)`, `PullRequestServiceImpl`.
- **Depends on:** M1-01.
- **Acceptance:** sync with PRs succeeds. Cache round-trip returns equal DTOs.
- **Verify:** integration test with a Redis container and a mocked `GitHubApiClient`.

#### M1-06 · P1 · BE · S — CORS for the SPA
- **Outcome:** `CorsConfigurationSource` driven by `APP_ALLOWED_ORIGINS` (default `http://localhost:5173`). Allows `Authorization` and `Content-Type`.
- **Acceptance:** a preflight from the allowed origin passes. Other origins are rejected.
- **Verify:** MockMvc preflight test; browser check in M2.

#### M1-07 · P2 · BE · S — Consistent JSON success responses
- **Outcome:** register returns `201` + `{id,email,name}`. Password change returns `204`. OAuth authorize returns `{url}`.
- **Acceptance:** no `text/plain` responses under `/api/**`.
- **Verify:** MockMvc.

#### M1-08 · P1 · BE · S — Remove dead and unsafe code
- **Outcome:** delete `POST /api/github/connect` (+ `ConnectGitHubRequest`, related service method), the inner `AuthController.TestController`, `S3TestController`, `VectorStoreController`, and unused `AIChatRequest/Response`. Fix the `S3Service` package. Keep `S3Service` only if D-08 says so.
- **Acceptance:** compile + tests pass. Endpoint list matches FEATURE_MATRIX after cleanup.
- **Verify:** `./mvnw verify`; check the OpenAPI listing once M2-02 lands.

#### M1-09 · P1 · BE · M — Encrypt GitHub tokens at rest
- **Outcome:** a JPA `AttributeConverter` with AES-GCM, key from `TOKEN_ENCRYPTION_KEY` (KMS later). The migration widens columns and makes `refresh_token` nullable (also needed by M3-01). Existing rows are re-encrypted or cleared (users reconnect).
- **Acceptance:** DB column values aren't plaintext tokens. The app still calls GitHub successfully.
- **Verify:** unit test for the converter; integration test reads the raw column via JDBC.

#### M1-10 · P2 · BE · S — Audit timestamps
- **Outcome:** `@EnableJpaAuditing` + `@CreatedDate/@LastModifiedDate` (or Hibernate `@CreationTimestamp`) on `BaseEntity`.
- **Verify:** an integration test asserts non-null `createdAt`/`updatedAt` after save/update.

### M2: Frontend foundation + authentication slice

#### M2-01 · P0 · BE · S — `GET /api/auth/me`
- **Outcome:** returns `{id,name,email,role,github:{connected,username,avatarUrl}}`, read from the DB. `/api/profile` GET stays as an alias, or is deprecated.
- **Verify:** MockMvc with and without a GitHub account.

#### M2-02 · P1 · BE · S — OpenAPI documentation
- **Outcome:** `springdoc-openapi-starter-webmvc-ui`. `/v3/api-docs` and `/swagger-ui` are public in local/dev only. A Bearer security scheme is declared.
- **Verify:** Swagger UI lists every endpoint. In the prod profile, the docs return 404.

#### M2-03 · P0 · FE · M — Scaffold the React app
- **Outcome:** `frontend/` with Vite + React + TypeScript (strict), ESLint + Prettier, Vitest + React Testing Library, MSW for API mocks, path aliases, `.env.example` (`VITE_API_BASE_URL`), and a CI job (lint, typecheck, test, build).
- **Structure:** `src/app` (router, providers), `src/features/<feature>/{api,components,pages,hooks}`, `src/components/ui` (design system), `src/lib` (api client, utils), `src/types` (generated from OpenAPI).
- **Depends on:** D-04 (repo layout), D-11 (stack).
- **Verify:** `npm run lint && npm run typecheck && npm test && npm run build` in CI.

#### M2-04 · P0 · FS · L — Auth flows
- **Outcome:** `/login` and `/register` pages (react-hook-form + zod mirroring backend validation), API client with Bearer injection and a 401 interceptor → logout + redirect, auth store (per D-05), `<RequireAuth>` route guard, session bootstrap via `/api/auth/me`, logout.
- **Depends on:** M1-03, M1-06, M2-01, M2-03.
- **Acceptance:** field-level server errors are shown (409 duplicate email, 401 bad credentials). A refresh keeps the session. An expired token redirects to login.
- **Verify:** RTL tests with MSW; a manual check against the real backend.

#### M2-05 · P0 · FE · L — App shell and design system
- **Outcome:** responsive layout (sidebar + topbar, mobile drawer), light/dark theme, and shared components: Button, Input, Select, Card, Badge (severity colours), Table, Tabs, Dialog, Toast, Skeleton, EmptyState, ErrorState (with retry), Pagination, CodeBlock. `NotFound` page and error boundary.
- **Acceptance:** works at 360 px width with no horizontal scroll. Keyboard navigable. Colour contrast AA.
- **Verify:** Vitest component tests; a manual viewport check.

#### M2-06 · P1 · FE · M — Settings: profile and password
- **Outcome:** `/settings/profile` (update name/email), `/settings/security` (change password). Success toasts and server-side validation errors.
- **APIs:** `PUT /api/profile`, `PUT /api/profile/password`.
- **Verify:** RTL tests; manual E2E.

#### M2-07 · P1 · FE/INF · M — Playwright E2E: auth journey
- **Outcome:** Playwright project against compose (backend + DB + Redis) covering register → login → dashboard shell → profile update → logout → guard redirect.
- **Verify:** passes locally and in CI (nightly or on PR).

### M3: GitHub connection, repositories & pull requests

#### M3-01 · P0 · BE · M — Correct the OAuth authorize and token handling
- **Outcome:** authorize URL built with `UriComponentsBuilder` (encoded), includes `scope` (OAuth App: `repo read:user`; GitHub App: none). Token exchange handles the `error` field, missing `expires_in` (non-expiring), and a null `refresh_token`. `GitHubTokenService` only refreshes when expiry is known.
- **Depends on:** D-02, M1-09 (nullable refresh column).
- **Verify:** unit tests with WireMock covering expiring and non-expiring token responses plus an error response.

#### M3-02 · P0 · BE · S — Callback redirects to the SPA
- **Outcome:** the callback returns `302` to `${APP_FRONTEND_URL}/github/callback?status=success|error&reason=…`. No tokens in the URL.
- **Verify:** MockMvc asserts the `Location` header for success and for invalid state.

#### M3-03 · P0 · BE · S — Connection status + disconnect with revocation
- **Outcome:** `GET /api/github/account` → `{connected, username, avatarUrl, connectedAt, lastSyncAt, syncStatus}`. `DELETE /api/github/disconnect` also calls GitHub's grant-revocation API (best effort) and evicts caches.
- **Verify:** integration tests (WireMock for GitHub).

#### M3-04 · P0 · BE · M — Asynchronous sync + manual resync
- **Outcome:** the callback only stores the account and enqueues a sync (`@Async` on a virtual-thread executor, or the D-12 job mechanism). Each repo is synced in its own transaction. `POST /api/github/sync` (rate-limited, 1 per minute). Sync status is stored on the account. RestClient connect/read timeouts are set. GitHub `403/429` rate-limit headers are respected.
- **Verify:** an integration test where the callback returns promptly with a slow WireMock; sync completes asynchronously and status moves to `COMPLETED`.

#### M3-05 · P0 · BE · S — Paged "my repositories" API
- **Outcome:** `GET /api/github/repositories?page&size&q&reviewEnabled` → `Page<RepositoryResponse>` for the current user, sorted by name.
- **Depends on:** M1-01, M1-02.
- **Verify:** MockMvc with paging and filter cases.

#### M3-06 · P1 · BE · S — Per-repository review toggle
- **Outcome:** migration adds `review_enabled boolean default false` (plus `auto_review`, `post_comments` per D-13). `PATCH /api/github/repositories/{id}`.
- **Verify:** MockMvc + ownership test.

#### M3-07 · P0 · BE · M — Scoped, paged PR APIs
- **Outcome:** `GET /api/github/repositories/{repoId}/pull-requests?state&page&size`, `GET /api/github/pull-requests/{id}` (detail includes the latest review status once M4 lands). Old PR routes are removed or redirected.
- **Verify:** MockMvc + ownership tests.

#### M3-08 · P0 · FE · M — Connect GitHub UX
- **Outcome:** onboarding card on the dashboard when not connected. "Connect GitHub" calls authorize and redirects the browser. The `/github/callback` page reads `status` and shows success/error, polling sync status. The settings section shows the connected account, Resync, and Disconnect (with confirm dialog).
- **APIs:** M3-01..M3-04.
- **Verify:** RTL + MSW; manual run with a real GitHub test account.

#### M3-09 · P0 · FE · M — Repositories list and detail
- **Outcome:** `/repositories` table/cards with search, filter (enabled, language), pagination, badges (private, fork, archived), and an enable-review toggle (optimistic update). `/repositories/:id` header with tabs: Pull Requests · Index (M6) · Settings.
- **Verify:** RTL; manual E2E.

#### M3-10 · P0 · FE · M — Pull request list and detail
- **Outcome:** PR tab with state filter (Open/Closed/Merged) and pagination. `/repositories/:id/pulls/:prId` shows title, author, branches, links to GitHub, and the review panel placeholder (filled by M4-11).
- **Verify:** RTL; manual E2E.

### M4: AI review engine (manual PR review)

#### M4-01 · P0 · BE · M — Review schema
- **Outcome:** migration: `pull_requests` + `head_sha`, `base_sha`, `additions`, `deletions`, `changed_files`. `reviews` (pr_id, head_sha, status, trigger, provider, model, summary, files_reviewed, tokens_in/out, error, started/completed_at, requested_by). `review_findings` (review_id, file_path, start_line, end_line, side, severity, category, cwe, title, description, recommendation, confidence, source LLM/RULE, status OPEN/DISMISSED, github_comment_id). Indexes on (pr_id, created_at) and (review_id). Entities + repositories.
- **Verify:** Flyway migrates on an empty DB and on a DB at V2; Hibernate validates.

#### M4-02 · P0 · BE · M — Fetch PR files and patches
- **Outcome:** `GitHubApiClient.getPullRequestFiles(owner, repo, number)` (paged, 3000-file cap) and `getPullRequest` (for SHAs). `GET /api/github/pull-requests/{id}/files` → `[{filename,status,additions,deletions,patch}]` (cached by head SHA).
- **Verify:** WireMock tests (including a binary file with no patch and a truncated patch).

#### M4-03 · P0 · BE · L — Diff parser and chunker
- **Outcome:** parse unified-diff hunks into added/context lines with new-file line numbers. Group into token-bounded chunks per file. Skip lockfiles, generated, vendored, and binary files.
- **Verify:** pure unit tests with fixture diffs (renames, deletions, multi-hunk, CRLF).

#### M4-04 · P0 · BE · M — Prompt v2 + structured-output validation
- **Outcome:** `prompts/pr-review.st` asks for findings with `file`, `startLine`, `endLine`, `severity`, `category`, `cwe?`, `confidence`, and treats the code as untrusted (prompt-injection guard). Output goes through Spring AI `BeanOutputConverter`. Findings are validated (line inside the diff, enum values) and invalid ones dropped and logged. Mock provider returns deterministic findings anchored to the real diff lines.
- **Verify:** unit tests over provider output fixtures (valid, malformed JSON, out-of-range lines).

#### M4-05 · P0 · BE · L — Async review job lifecycle
- **Outcome:** `ReviewService.requestReview(prId, trigger)` → dedupe on (pr, head_sha) → `QUEUED` → worker → `RUNNING` → `COMPLETED`/`FAILED`. Per-chunk provider calls with a timeout. Partial failure is recorded. Startup recovery re-queues stale `RUNNING` jobs.
- **Depends on:** M4-01..M4-04, D-12.
- **Verify:** integration test with the mock provider and WireMock GitHub runs the full lifecycle; a failure-path test.

#### M4-06 · P0 · BE · M — Review APIs
- **Outcome:** `POST /api/github/pull-requests/{id}/reviews` (202 + review), `GET /api/github/pull-requests/{id}/reviews`, `GET /api/reviews/{id}` (with findings, grouped counts), `GET /api/reviews?repositoryId&status&page`, `PATCH /api/findings/{id}` `{status}`. All ownership-scoped.
- **Verify:** MockMvc + ownership tests.

#### M4-07 · P0 · BE · S — Cost guardrails
- **Outcome:** config limits: max files, max diff lines, max tokens per review, daily reviews per user, and a provider kill switch. Over the limit → review `SKIPPED` with a reason. `ai.provider=mock` stays the default in all non-prod profiles.
- **Verify:** unit tests per limit.

#### M4-08 · P2 · BE · S — Harden the Playground endpoint
- **Outcome:** `POST /api/ai/review` takes `{code, language?}` with `@Size(max=20000)`, is rate-limited, and is not persisted.
- **Verify:** MockMvc.

#### M4-09 · P1 · BE · M — Deterministic vulnerability rules
- **Outcome:** a `RuleEngine` run on added lines: hard-coded secrets (AWS keys, GitHub tokens, private keys, generic high-entropy), SQL concatenation into `createQuery`/`execute`, `Runtime.exec` with input, disabled TLS verification, weak hashes (MD5/SHA1 for passwords). Each rule maps to a CWE. Results merge with LLM findings (dedupe on file+line+cwe).
- **Verify:** unit tests per rule (positive and negative fixtures).

#### M4-10 · P1 · BE · S — Dashboard summary API
- **Outcome:** `GET /api/dashboard/summary` → counts (repos enabled, PRs open, reviews last 30 days, findings by severity), recent reviews (5), top risky repos.
- **Verify:** integration test with seeded data.

#### M4-11 · P0 · FE · M — Run review from the PR page
- **Outcome:** "Run AI review" button (disabled while running), status timeline with polling (TanStack Query `refetchInterval` until terminal), list of past reviews for the PR, error state with retry.
- **Verify:** RTL + MSW lifecycle test.

#### M4-12 · P0 · FE · L — Review report page
- **Outcome:** `/reviews/:id` with summary header (counts by severity), filters (severity, category, file, status), findings grouped by file, a diff viewer (`react-diff-view` or similar) with inline finding markers, a finding detail drawer (description, recommendation, CWE link), and dismiss/reopen.
- **Verify:** RTL; a manual check with a mock review.

#### M4-13 · P1 · FE · M — Review history + dashboard
- **Outcome:** `/reviews` paged table with filters. `/dashboard` stat cards, severity breakdown chart, recent reviews, and the onboarding checklist (connect GitHub → enable repo → run first review).
- **Verify:** RTL; manual.

#### M4-14 · P2 · FE · S — Playground page
- **Outcome:** `/playground` code editor (Monaco or CodeMirror), language select, findings list.
- **Verify:** RTL.

#### M4-15 · P1 · BE · S — Real provider smoke test (manual, opt-in)
- **Outcome:** documented procedure to run one review with a real provider key from `.env`, under a budget cap set in the provider console. The result is recorded in the PR.
- **Depends on:** D-03 and your approval (paid call).
- **Verify:** manual. Not part of CI.

### M5: Webhooks, automated reviews & inline comments

#### M5-01 · P0 · BE · M — GitHub webhook endpoint
- **Outcome:** `POST /api/webhooks/github` (public), raw-body HMAC SHA-256 verification against `GITHUB_WEBHOOK_SECRET` with constant-time compare, 401 on mismatch. Routes `ping`, `pull_request`, `push`, `installation*` (if GitHub App). Responds within 1 s (processing async).
- **Verify:** MockMvc with signed fixture payloads (valid, invalid, missing signature).

#### M5-02 · P0 · BE · S — Delivery idempotency and audit
- **Outcome:** `webhook_deliveries` table (delivery_id unique, event, action, repo_github_id, status, error, received/processed_at). Duplicate deliveries are no-ops.
- **Verify:** an integration test sends the same delivery twice and gets one review.

#### M5-03 · P0 · BE · M — Auto-review on PR events
- **Outcome:** `opened`/`synchronize`/`reopened`/`ready_for_review` on a `review_enabled` + `auto_review` repo → upsert PR (with SHAs) → `requestReview(trigger=WEBHOOK)`. Drafts are skipped per setting. `closed` updates state and merged.
- **Depends on:** M4-05, M5-01, M5-02.
- **Verify:** integration test from a webhook fixture through to a completed review (mock provider).

#### M5-04 · P0 · BE · M — Publish inline review comments
- **Outcome:** `POST /api/reviews/{id}/publish` (and auto-publish if `post_comments`) creates one GitHub PR review (`event=COMMENT`) with inline comments for findings at or above the threshold, anchored to `head_sha` + line + side, with a summary body. Stores `github_comment_id`. Re-publishing doesn't duplicate.
- **Depends on:** D-02 (identity used to post), M4-06.
- **Verify:** WireMock contract test; manual run on a test repo.

#### M5-05 · P1 · FS · M — Review settings
- **Outcome:** BE: `GET/PUT /api/settings` (defaults: severity threshold, auto-review, post comments, skip drafts) and per-repo overrides via M3-06. FE: `/settings/reviews` form and the repository Settings tab.
- **Verify:** MockMvc; RTL.

#### M5-06 · P1 · FE · S — Publish to GitHub UX
- **Outcome:** "Publish to GitHub" button on the review page, a published state, and links to the GitHub comments.
- **Verify:** RTL.

#### M5-07 · P2 · INF · S — Local webhook development guide
- **Outcome:** docs and a script for smee.io or ngrok forwarding to `localhost:8080/api/webhooks/github`, and test-repo setup.
- **Verify:** follow the guide once end to end.

### M6: Indexing, semantic search & RAG

#### M6-01 · P0 · BE · S — Flyway-managed vector table and index jobs
- **Outcome:** migration creates `vector_store` (id uuid, content, metadata jsonb, embedding vector(384), HNSW cosine index, GIN on metadata) matching the Spring AI schema. `repository_index_jobs` table. Set `initialize-schema=false`.
- **Verify:** migration test; a Spring AI `VectorStore` add/search round trip.

#### M6-02 · P0 · BE · M — Repository snapshot fetch and file filter
- **Outcome:** download the default-branch tarball (`/repos/{o}/{r}/tarball/{ref}`) streamed to a temp dir. Include source extensions. Exclude vendored/generated/binary/large (> 200 KB) files and respect `.gitignore`-like patterns. Clean up the temp files.
- **Verify:** unit tests with a fixture tarball; WireMock for the download.

#### M6-03 · P0 · BE · M — Code chunker
- **Constraint:** the embedding tokenizer truncates at 128 tokens (see D-06 outcome). Size chunks by token count, not just lines.
- **Outcome:** line-window chunker (e.g. 60 lines, 10 overlap) with a language-aware split on top-level declarations where cheap. Metadata `{userId, repositoryId, path, language, startLine, endLine, commitSha}`.
- **Verify:** unit tests.

#### M6-04 · P0 · BE · L — Indexing job + APIs
- **Outcome:** `POST /api/github/repositories/{id}/index` → job (QUEUED/RUNNING/COMPLETED/FAILED, files and chunks counts, progress). Deletes old vectors for the repo, embeds in batches, then stores `last_indexed_sha`. `GET …/index-status`.
- **Verify:** integration test that indexes a small fixture repo and asserts the chunk count.

#### M6-05 · P0 · BE · M — Semantic search API
- **Outcome:** `POST /api/search {repositoryId?, query, topK≤20, language?}` → `[{path, startLine, endLine, score, snippet, githubUrl}]`. Filter expressions are always scoped to the current user's repositories.
- **Verify:** integration test against an indexed fixture (expected file in top 3); a cross-user isolation test.

#### M6-06 · P1 · BE · M — RAG context in reviews
- **Outcome:** for each review chunk, retrieve top-k related chunks from the same repo (excluding the changed file's own old version where possible) and add them to the prompt under a token budget. Record the references used in `reviews.context_refs`.
- **Verify:** unit test for prompt assembly; a comparison on 3 sample PRs with and without RAG (qualitative notes in the PR).

#### M6-07 · P2 · BE · M — Incremental reindex on push
- **Outcome:** `push` to the default branch of an indexed repo → re-embed only added/modified files, delete vectors for removed files, and update `last_indexed_sha`.
- **Verify:** integration test with a push fixture.

#### M6-08 · P1 · FE · S — Index tab
- **Outcome:** index status, last indexed SHA/time, and a Start/Reindex button with progress polling.
- **Verify:** RTL.

#### M6-09 · P0 · FE · M — Semantic search page
- **Outcome:** `/search` with repo picker, query box, results with highlighted snippets, a path/line link to GitHub, an empty state that suggests indexing, and debounced recent queries.
- **Verify:** RTL; manual.

#### M6-10 · P2 · BE · S — Retrieval quality check
- **Outcome:** a 15-query eval set against DevPulse's own repo, reporting recall@5. Run on demand.
- **Verify:** script output committed under `docs/`.

### M7: Hardening & end-to-end testing

#### M7-01 · P0 · FE/INF · L — Full E2E suite
- **Outcome:** Playwright journeys (auth, connect GitHub via a WireMock GitHub stub, enable repo, run review, view findings, publish, search). Runs in CI against compose with mock AI.
- **Verify:** green in CI.

#### M7-02 · P1 · BE · M — Rate limiting and size limits
- **Outcome:** Redis-backed limits on login (brute force), register, review trigger, sync, search, and playground. `server.tomcat.max-swallow-size` and request-body limits.
- **Verify:** MockMvc tests hit the limits and get 429.

#### M7-03 · P1 · BE · M — Resilience
- **Outcome:** timeouts and retries with backoff for GitHub and the LLM, GitHub rate-limit awareness, job retry counts, and a dead state.
- **Verify:** WireMock fault-injection tests.

#### M7-04 · P0 · BE/INF · S — Security headers and exposure
- **Outcome:** HSTS, `X-Content-Type-Options`, frame denial, and a CSP for the SPA host. Actuator: only `health` (no details) public. Dependency scanning (Dependabot / OWASP dependency-check) and gitleaks in CI.
- **Verify:** curl header checks; the CI job is present.

#### M7-05 · P2 · FE · M — Accessibility and responsive QA
- **Outcome:** axe checks in Playwright, keyboard-only pass, and 360/768/1280 px screenshots reviewed.
- **Verify:** zero serious axe violations.

#### M7-06 · P1 · FS · M — Security review pass
- **Outcome:** OWASP ASVS L1 checklist filled in, with findings fixed or ticketed (JWT storage, CSRF stance, SSRF in GitHub URLs, prompt injection, log redaction of tokens).
- **Verify:** checklist committed in `docs/`.

### M8: Deployment, CI/CD, monitoring & docs

#### M8-01 · P0 · BE · S — Production profile
- **Outcome:** `application-prod.properties` with everything from env, Swagger off, JSON logs, `ddl-auto=validate`, graceful shutdown, and Hikari sizing.
- **Verify:** start with the prod profile locally against compose.

#### M8-02 · P0 · INF · S — Secrets management
- **Outcome:** secrets in AWS SSM Parameter Store/Secrets Manager, injected into ECS task definitions. Nothing in the image.
- **Depends on:** D-07.
- **Verify:** `docker history`/image inspection shows no secrets.

#### M8-03 · P0 · INF · XL (split) — AWS infrastructure as code
- **Outcome:** Terraform or CDK (D-07) for VPC, RDS PostgreSQL 17 with pgvector, Redis, ECR, ECS Fargate service behind an ALB, ACM certificate, and Route 53. Split into network / data / compute PRs.
- **Verify:** `plan` reviewed by you before any `apply` (cost-incurring; explicit approval each time).

#### M8-04 · P0 · INF · M — Frontend hosting
- **Outcome:** S3 (private) + CloudFront with OAC, SPA fallback to `index.html`, security headers, and cache policy.
- **Verify:** deployed URL loads and deep links work.

#### M8-05 · P1 · INF/BE · M — Observability
- **Outcome:** Micrometer metrics (review duration, LLM tokens, GitHub calls, job failures) to CloudWatch, structured logs with request ID, alarms (5xx rate, job failures, DB CPU).
- **Verify:** dashboard screenshot; a test alarm fires.

#### M8-06 · P0 · INF · M — CD pipelines
- **Outcome:** GitHub Actions with OIDC to AWS (no long-lived keys). On a `main` merge: test → build image → push to ECR → deploy to ECS (staging), then manual approval → prod. Frontend: build → S3 sync → CloudFront invalidation.
- **Verify:** a deployment run log.

#### M8-07 · P1 · Docs · M — Documentation
- **Outcome:** `README.md` (overview, quick start, architecture diagram), `docs/architecture.md`, `docs/runbook.md`, ADRs for each decision in TECHNICAL_DECISIONS, and an API link to OpenAPI. Replace `HELP.md`. Stop excluding `README.md` in `.dockerignore` only if needed.
- **Verify:** a fresh-clone dry run by following the README.

#### M8-08 · P0 · INF · S — Cost controls
- **Outcome:** AWS Budgets alert, LLM provider spend cap, and a documented teardown (`destroy`) for non-prod.
- **Verify:** budget alert email received in a test.
