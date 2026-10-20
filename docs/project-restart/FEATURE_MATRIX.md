# Feature Matrix

**Status:** ✅ Completed (code complete for its scope, no defects found on review) · 🟡 Partial · ❌ Missing · ❓ Unverified (code exists but may not work; needs a run).
**Runtime verification:** none. The project hasn't been compiled or run on this machine, and there are no automated tests beyond `contextLoads`. "Completed" means *by code review only*.

## 1. Backend

| Area | Feature | Status | Evidence | Gap / next task |
|---|---|---|---|---|
| Foundation | Spring Boot 3.5.6 / Java 21 project, layered packages | ✅ | `pom.xml`, package layout | Upgrade decision D-09 |
| Foundation | Build compiles | ❓ | No JDK here | M0-02 |
| Auth | Register (BCrypt, validation, duplicate email) | ✅ | `UserServiceImpl.register` | Return JSON not text (M1-07) |
| Auth | Login → JWT (HS, `jti`, expiry) | ✅ | `JwtService`, `UserServiceImpl.login` | Secret rotation (M0-01) |
| Auth | Logout via Redis blacklist | 🟡 | `AuthController.logout`, `JwtBlacklistServiceImpl` | Malformed-token crash (M1-03) |
| Auth | JWT filter error handling (401 JSON) | ❌ | `JwtAuthenticationFilter.java:53` throws | M1-03 |
| Auth | Current user (`/me`) | 🟡 | `GET /api/profile` (from token claims) | Read from DB, include GitHub status (M2-01) |
| Auth | Profile update / change password | ✅ | `ProfileController`, `UserServiceImpl` | Use `ResourceNotFoundException` (M1-04) |
| Auth | Roles USER/ADMIN + method security | ✅ | `Role`, `@PreAuthorize` | — |
| Auth | Refresh tokens / remember-me | ❌ | — | Decision D-05 |
| Auth | CORS for SPA | ❌ | `SecurityConfig` | M1-06 |
| GitHub | OAuth authorize + signed single-use state | ✅ | `GitHubOAuthStateServiceImpl` | URL-encode, scopes (M3-01) |
| GitHub | OAuth callback / token exchange | ❓ | `GitHubOAuthServiceImpl` | NPE for non-expiring tokens; redirect to SPA (M3-01, M3-02) |
| GitHub | Token refresh | 🟡 | `GitHubTokenServiceImpl` | Only valid for GitHub-App expiring tokens (D-02) |
| GitHub | Token encryption at rest | ❌ | plaintext columns | M1-09 |
| GitHub | Manual connect `/api/github/connect` | ❓ (likely broken) | never sets NOT-NULL `refreshToken` | Remove (M1-08) |
| GitHub | Disconnect | 🟡 | cascades locally | Revoke token at GitHub (M3-03) |
| GitHub | Connection status API | ❌ | — | M3-03 |
| Repos | Sync user repos (paged, upsert) | 🟡 | `GitHubRepositoryServiceImpl.synchronizeRepositories` | Runs synchronously in callback (M3-04) |
| Repos | List my repos API | 🟡 | `/account/{accountId}` w/o ownership | M3-05 |
| Repos | Repo detail API | ❓ | returns entity, IDOR, token leak risk | M1-01, M1-02 |
| Repos | Enable/disable review per repo | ❌ | — | M3-06 |
| Repos | Redis repo cache (10-min TTL) | ✅ | `GitHubCacheServiceImpl` | Key by user + evict on toggle |
| PRs | Sync PRs (paged, state=all) | ❓ | `PullRequestServiceImpl.synchronizePullRequests` | Cache serialization bug A9 (M1-05) |
| PRs | PR list/detail APIs | 🟡 | `PullRequestController` (entities, no paging, IDOR) | M1-01, M3-07 |
| PRs | PR files / diff fetch | ❌ | — | M4-02 |
| PRs | `head_sha`/`base_sha`, stats | ❌ | — | M4-01 |
| Webhooks | Endpoint + HMAC signature verify | ❌ | — | M5-01 |
| Webhooks | Delivery idempotency / audit log | ❌ | — | M5-02 |
| Webhooks | `pull_request` opened/synchronize → auto review | ❌ | — | M5-03 |
| Webhooks | `push` → incremental reindex | ❌ | — | M6-07 |
| AI | Provider abstraction (mock / OpenAI) | ✅ | `AIProviderConfig`, `MockAIProvider`, `OpenAIProvider` | Add provider choice D-03 |
| AI | Snippet review endpoint | 🟡 | `POST /api/ai/review` raw string, no limits | M4-08 (Playground) |
| AI | Structured findings JSON prompt | 🟡 | `prompts/code-review.st` | Needs file/line anchors (M4-04) |
| AI | PR review pipeline (diff → chunks → LLM → findings) | ❌ | — | M4-03..M4-06 |
| AI | Review + findings persistence, history | ❌ | — | M4-01 |
| AI | Async job execution + status | ❌ | — | M4-05 |
| AI | Cost/size guardrails, rate limiting | ❌ | — | M4-07 |
| Security scan | Vulnerability detection | 🟡 | LLM category `SECURITY` only | Deterministic rules + CWE (M4-09) |
| Comments | Post inline PR review comments to GitHub | ❌ | — | M5-04 |
| Vector | PGVector store config (384-d, HNSW, cosine) | 🟡 | properties; test controller only | Flyway-managed table (M6-01) |
| Vector | Local ONNX embeddings | ❓ | `spring.ai.embedding.transformer.*`, 90 MB model in Git | Externalize model (M0-05) |
| RAG | Repo snapshot fetch + chunking + indexing | ❌ | — | M6-02..M6-04 |
| RAG | Semantic search API | ❌ | `/api/vector/search` is a toy | M6-05 |
| RAG | Retrieved context injected into reviews | ❌ | — | M6-06 |
| Storage | S3 service | 🟡 | `S3ServiceImpl` + unsafe test controller | Remove controller (M1-08); use only if needed (D-08) |
| Errors | Global JSON error handler | 🟡 | 5 handlers, no catch-all | M1-04 |
| Persistence | Flyway migrations | 🟡 | V1 only; `ddl-auto=update` locally | M0-06 |
| Persistence | Audit timestamps | ❌ | `BaseEntity` fields never set | M1-10 |
| Pagination | Paged list endpoints | ❌ | — | M3-05, M3-07 |
| API docs | OpenAPI/Swagger | ❌ | — | M2-02 |
| Tests | Unit / slice / integration tests | ❌ | only `contextLoads` | M0-07 + per-task tests |
| Observability | Actuator health | ✅ | `/actuator/health` | Hide details publicly (M7-04) |
| Observability | Structured logs, metrics, tracing | ❌ | — | M8-05 |

## 2. Infrastructure

| Feature | Status | Evidence | Gap |
|---|---|---|---|
| Dockerfile (multi-stage, non-root) | ✅ | `Dockerfile` | Exclude ONNX from image (M0-05) |
| Compose for local dev (Postgres+pgvector, Redis) | ❌ | Postgres removed in `ecba859` | M0-03 |
| Compose for backend + Redis against RDS | ❓ (likely broken) | Redis host mismatch A6 | M0-04 |
| `.env.example` | 🟡 | placeholders; missing AI/AWS/webhook keys | M0-04 |
| AWS RDS PostgreSQL | ❓ | hostname in config; state/cost unknown | Manual check (D-07) |
| AWS S3 bucket | ❓ | bucket name in config | Manual check |
| AWS compute (ECS/EC2/App Runner) | ❌ | — | M8-03 |
| Frontend hosting (S3 + CloudFront) | ❌ | — | M8-04 |
| CI (build + test) | ❌ | no `.github/workflows` | M0-08 |
| CD (deploy) | ❌ | — | M8-06 |
| Secrets management (SSM/Secrets Manager) | ❌ | — | M8-02 |
| README / runbook | ❌ | `HELP.md` boilerplate | M8-07 |

## 3. Frontend

| Feature | Status |
|---|---|
| React application (any) | ❌ Doesn't exist in this repo, on disk, or on the GitHub account |
| Auth screens, dashboard, GitHub connect, repos, PR review, findings, search, history, settings | ❌ All missing. Planned in ROADMAP M2–M6 |

## 4. Frontend ↔ Backend integration map

Status = backend readiness for that screen.

| Screen / route | User action | Backend API (target) | Backend status | Task |
|---|---|---|---|---|
| `/register` | Create account | `POST /api/auth/register` | ✅ exists (switch to JSON) | M2-04 |
| `/login` | Sign in | `POST /api/auth/login` | ✅ exists | M2-04 |
| (global) | Sign out | `POST /api/auth/logout` | 🟡 fix M1-03 | M2-04 |
| (global) | Load session | `GET /api/auth/me` | ❌ (adapt `/api/profile`) | M2-01 |
| `/onboarding/github`, `/settings/github` | Connect GitHub | `GET /api/github/oauth/authorize` → GitHub → `GET /api/github/oauth/callback` → **302 to SPA** `/github/callback?status=` | 🟡 needs redirect | M3-01, M3-02 |
| same | Connection status / disconnect | `GET /api/github/account`, `DELETE /api/github/disconnect` | ❌ / ✅ | M3-03 |
| `/repositories` | List repos, search/filter, resync | `GET /api/github/repositories?page&q`, `POST /api/github/sync` | 🟡 / ❌ | M3-04, M3-05 |
| `/repositories/:id` | Toggle auto-review, view status | `GET/PATCH /api/github/repositories/{id}` | ❌ | M3-06 |
| `/repositories/:id` (PR tab) | Browse PRs | `GET /api/github/repositories/{id}/pull-requests?state&page` | 🟡 | M3-07 |
| `/repositories/:id/pulls/:prId` | PR detail + changed files | `GET /api/github/pull-requests/{id}`, `GET …/{id}/files` | 🟡 / ❌ | M3-07, M4-02 |
| same | Run AI review | `POST /api/github/pull-requests/{id}/reviews` → `GET /api/reviews/{id}` (poll) | ❌ | M4-05, M4-06 |
| `/reviews/:id` | Findings report, filter by severity/category/file, dismiss | `GET /api/reviews/{id}`, `PATCH /api/findings/{id}` | ❌ | M4-06 |
| same | Publish inline comments to GitHub | `POST /api/reviews/{id}/publish` | ❌ | M5-04 |
| `/reviews` | Review history | `GET /api/reviews?repositoryId&status&page` | ❌ | M4-06 |
| `/dashboard` | Stats, recent reviews, severity breakdown | `GET /api/dashboard/summary` | ❌ | M4-10 |
| `/repositories/:id` (Index tab) | Index codebase, status | `POST /api/github/repositories/{id}/index`, `GET …/index-status` | ❌ | M6-04 |
| `/search` | Semantic code search | `POST /api/search` `{repositoryId, query, topK}` | ❌ | M6-05 |
| `/playground` | Paste snippet → review | `POST /api/ai/review` (JSON DTO) | 🟡 | M4-08 |
| `/settings` | Profile, password, review prefs | `GET/PUT /api/profile`, `PUT /api/profile/password`, `GET/PUT /api/settings` | ✅ / ✅ / ❌ | M2-06, M5-05 |
| (webhook, no UI) | GitHub events | `POST /api/webhooks/github` | ❌ | M5-01..M5-03 |
