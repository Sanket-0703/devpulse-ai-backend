# DevPulse AI — Project Audit (Restart, 2026-10-21)

Audit of `Sanket-0703/devpulse-ai-backend`, branch `Sanket_Restart1` at `ecba859` (identical to `origin/main`).
Method: static reading of every source, config, migration, Docker, and Git-history file. **Nothing was built or run** — this Mac has no JDK, Docker, Postgres, or Redis yet (see [MAC_SETUP.md](MAC_SETUP.md)). Every runtime behaviour below is therefore **Unverified** unless stated otherwise.

Related documents: [FEATURE_MATRIX.md](FEATURE_MATRIX.md) · [ROADMAP.md](ROADMAP.md) · [TASK_BACKLOG.md](TASK_BACKLOG.md) · [TECHNICAL_DECISIONS.md](TECHNICAL_DECISIONS.md)

---

## 1. Headline findings

1. **Backend only, no frontend.** No `package.json`, no React code in this repo, nowhere else in the home directory, and no frontend repository on the GitHub account (public repos checked). The frontend must be built from scratch.
2. **The repository is public and contains secrets.** `src/main/resources/application.properties` has had a literal JWT signing secret (59 chars) and a literal local datasource password since the first commit (`36edbdd`). It also publishes the AWS RDS hostname (`application-docker.properties`) and the S3 bucket name. The JWT secret and any password reused elsewhere must be rotated, whether or not the history is rewritten. See TECHNICAL_DECISIONS D-01.
3. **A 90 MB ONNX model is committed** (`src/main/resources/embeddings/model.onnx`, plus a 711 KB tokenizer) in the latest commit. It's close to GitHub's 100 MB hard limit, permanently bloats clone size, and is baked into every Docker image.
4. **Zero meaningful tests.** The only test is `BackendApplicationTests.contextLoads()`, which needs live Postgres, Redis, and the ONNX model, so it cannot pass in a clean environment or CI.
5. **The product's core isn't built yet.** Auth, GitHub OAuth, and repository/PR sync exist. These don't: PR diff fetching, review persistence, webhook handling, inline GitHub comments, codebase indexing, semantic search, and RAG. The AI layer is a stateless "paste code → findings" endpoint that uses a mock provider by default. Vector store and S3 exist only as test controllers.
6. **Several implemented paths are probably broken at runtime** (from reading the code, not yet reproduced): the Docker Redis hostname, the pull-request cache serialization, OAuth token-expiry handling for OAuth Apps, and the manual GitHub connect endpoint. Details in §6.
7. **The old progress image (`Basic Roadmap.jpeg`, untracked) is stale.** It shows Spring Security/Redis/Docker/Spring AI at 0%, but code for all of them exists. Treat it as historical.

---

## 2. Repository structure

```
devpulse-ai-backend/
├── pom.xml                      Spring Boot 3.5.6, Java 21, Spring AI 1.1.8, AWS SDK 2.47.3
├── mvnw / .mvn/                 Maven wrapper (no system Maven needed)
├── Dockerfile                   Multi-stage temurin:21 build → JRE, non-root user, curl healthcheck
├── compose.yaml                 redis + backend (Postgres removed in ecba859; DB = AWS RDS)
├── .env.example                 Placeholder values only (checked: no real secrets)
├── .dockerignore / .gitignore
├── HELP.md                      Spring Initializr boilerplate (references Boot 4.1.0 docs — mismatch)
├── Basic Roadmap.jpeg           Untracked, stale progress screenshot
└── src/
    ├── main/java/com/devpulse/backend/
    │   ├── BackendApplication.java
    │   ├── client/              GitHubApiClient (+impl) — RestClient to api.github.com
    │   ├── common/              BaseEntity, RedisKeys
    │   ├── config/              Security, JWT, GitHub OAuth, Redis, S3, AI (ChatClient, provider switch)
    │   ├── controller/          11 controllers (see §4)
    │   ├── dto/ + dto/github/   Request/response DTOs, GitHub API DTOs
    │   ├── entity/              User, Role, GitHubAccount, GitHubRepository, PullRequest, PullRequestState
    │   ├── exception/           GlobalExceptionHandler + 6 custom exceptions
    │   ├── repository/          4 Spring Data JPA repositories
    │   ├── security/            JwtService, JwtAuthenticationFilter, CustomUserDetailsService, principal
    │   └── service/ + impl/     14 service interfaces / implementations
    ├── main/resources/
    │   ├── application.properties          local profile (literal secrets — see §1)
    │   ├── application-docker.properties   docker profile (RDS URL hard-coded)
    │   ├── db/migration/V1__baseline.sql   pg_dump of Hibernate-generated schema (4 tables)
    │   ├── prompts/code-review.st          system prompt for JSON findings
    │   └── embeddings/                     model.onnx (90 MB) + tokenizer.json
    └── test/java/.../BackendApplicationTests.java   contextLoads only
```

Layering is consistent: controller → service interface → `service/impl` → Spring Data repository, with Lombok DTOs/entities. New code should follow it. One exception: `service/S3Service.java` declares `package com.devpulse.backend.service.interfaces`, which doesn't match its directory.

## 3. Git history

| Commit | Date | Message | Content |
|---|---|---|---|
| `36edbdd` | 2026-08-08 | Initial commit: Spring Security and JPA foundation | User, register/login, JWT, profile, admin |
| `eaa8ba5` | 2026-08-09 | complete GitHub repository and pull request modules | GitHubAccount/Repository/PullRequest entities, CRUD-style controllers |
| `0d6960d` | 2026-08-13 | phase 7 and phase 7 done | GitHub OAuth, token refresh, GitHub API client, Redis (state, blacklist, cache), repo/PR sync |
| `cccfdf6` | 2026-08-13 | productionize DevPulse with Docker Compose | Dockerfile, compose (postgres+redis+backend), Flyway V1 baseline, docker profile |
| `ecba859` | 2026-10-21 | work till july | Spring AI (OpenAI/mock), PGVector + local ONNX embeddings, S3, AI review endpoint; compose switched from local Postgres to RDS |

- Only branches are `main` and `Sanket_Restart1`, both at `ecba859`. Nothing is unpushed.
- Commit messages are inconsistent ("phase 7 and phase 7 done", "work till july"). Use Conventional Commits from now on.
- Repository visibility: **public** (checked via the unauthenticated GitHub API).

## 4. API surface (as implemented)

All paths need a Bearer JWT except where marked *public*.

| Method | Path | Controller | Notes / defects |
|---|---|---|---|
| POST | `/api/auth/register` | AuthController | *public*. Returns plain text. |
| POST | `/api/auth/login` | AuthController | *public*. Returns `{token, type}`. No refresh token. |
| POST | `/api/auth/logout` | AuthController | *public* (under `/api/auth/**`). Blacklists the JWT `jti` in Redis. Throws if the token is malformed. |
| GET | `/api/test` | `AuthController.TestController` | **Dead code.** A non-static inner `@RestController` isn't registered by component scanning. |
| GET/PUT | `/api/profile` | ProfileController | GET builds the response from the JWT principal (DB not re-read). |
| PUT | `/api/profile/password` | ProfileController | |
| GET | `/api/admin/dashboard` | AdminController | Placeholder string. |
| POST | `/api/github/connect` | GitHubController | Manual token connect. **Broken:** never sets `refreshToken`, which is `NOT NULL`. Duplicates OAuth. |
| DELETE | `/api/github/disconnect` | GitHubController | Cascades (orphanRemoval) to repos and PRs. Doesn't revoke the GitHub token. |
| GET | `/api/github/oauth/authorize` | GitHubOAuthController | Returns the authorize URL as a string. `redirect_uri` isn't URL-encoded and no `scope` is set. |
| GET | `/api/github/oauth/callback` | GitHubOAuthController | *public*. Checks the HMAC + Redis state, exchanges the code, then **synchronously** syncs all repos and all PRs inside one transaction. Returns plain text, with no redirect to a UI. |
| GET | `/api/github/repositories/{id}` | GitHubRepositoryController | **Returns a JPA entity**, with no ownership check (IDOR). The lazy `githubAccount` can serialize **access/refresh tokens**. |
| GET | `/api/github/repositories/github/{githubId}` | 〃 | Same issues. |
| GET | `/api/github/repositories/account/{accountId}` | 〃 | Cache DTO (safe shape) but **any user can read any account's repos**. |
| DELETE | `/api/github/repositories/github/{githubId}` | 〃 | Any user can delete any repo row. |
| GET | `/api/github/pull-requests/{id}`, `/github/{githubId}`, `/repository/{repositoryId}`, `/repository/{id}/state/{state}`, `/repository/{id}/number/{n}` | PullRequestController | Return entities (lazy proxy + cycle risk), no ownership checks, no pagination. |
| DELETE | `/api/github/pull-requests/{id}` | 〃 | Any user can delete. |
| POST | `/api/ai/review` | AIController | Raw `String` body, no size limit, no persistence. `ai.provider=mock` by default. |
| POST | `/api/vector/test`, GET `/api/vector/search` | VectorStoreController | Test-only: one hard-coded document. |
| POST/GET/DELETE | `/api/test/s3/**` | S3TestController | **Test endpoints against a "prod" bucket.** Any user can upload, overwrite, download, or delete any key (key = original filename). |
| GET | `/actuator/health` | Actuator | *public*. Docker profile shows full details. |

## 5. Data model

Flyway `V1__baseline.sql` (a pg_dump of the Hibernate-generated schema) defines `users`, `github_accounts` (1:1 user, tokens stored **in plaintext**), `github_repositories`, and `pull_requests` (`state` ∈ OPEN/CLOSED, plus a `merged` flag).
Spring AI creates `vector_store` at runtime (`initialize-schema=true`, 384-dim, HNSW, cosine). That table isn't in Flyway.

Gaps:
- `created_at`/`updated_at` are never populated. `BaseEntity` has no `@CreationTimestamp`/`@UpdateTimestamp` or auditing.
- No tables for reviews, findings, webhook deliveries, index jobs, or user settings.
- No PR `head_sha`/`base_sha`, which reviews need in order to anchor comments to a commit.
- The local profile uses `ddl-auto=update` with `baseline-on-migrate`, so Hibernate and Flyway can drift. Docker uses `validate`.

## 6. Defects, risks, and technical debt (evidence)

| # | Severity | Finding | Evidence |
|---|---|---|---|
| A1 | Critical | Literal JWT secret + local DB password in a **public** repo since first commit | `application.properties` (`jwt.secret`, `spring.datasource.password`), every commit |
| A2 | Critical | Entities returned from controllers can leak GitHub tokens via `GitHubRepository.githubAccount` | `GitHubRepositoryController.java`, `entity/GitHubAccount.java` |
| A3 | Critical | No resource ownership checks (IDOR) on repository/PR read & delete | `GitHubRepositoryController`, `PullRequestController`, services take raw ids |
| A4 | High | S3 test controller lets any authenticated user write/delete objects in the prod bucket | `controller/S3TestController.java`, `S3ServiceImpl.java:28` |
| A5 | High | GitHub tokens stored unencrypted | `entity/GitHubAccount.java` |
| A6 | High | Docker profile Redis host `devpulse-redis` doesn't match compose service `redis`. `REDIS_HOST` env var is ignored, and `DB_HOST` is unused because the RDS URL is hard-coded | `application-docker.properties:22`, `:5`, `compose.yaml` |
| A7 | High | Expired/forged JWT → `extractTokenId` throws inside the filter → unhandled (non-JSON 401/403/500) | `JwtAuthenticationFilter.java:53` |
| A8 | High | OAuth callback assumes expiring tokens. For a classic OAuth App, `expires_in` is absent → `plusSeconds(null)` NPE, and `refresh_token` is null against a `NOT NULL` column | `GitHubOAuthServiceImpl.java:117-126`, V1 schema |
| A9 | High | PR cache serializes JPA entities (`PullRequest → GitHubRepository → pullRequests/githubAccount`), likely causing infinite recursion or token leakage into Redis. The cached read path also casts `JsonNode` to `List<PullRequest>` (ClassCastException) | `PullRequestServiceImpl.java:232,250` |
| A10 | High | Full repo + PR sync runs synchronously inside the OAuth callback HTTP request and one DB transaction. No timeouts or rate-limit handling | `GitHubOAuthServiceImpl.java:141`, `GitHubApiClientImpl` |
| A11 | Medium | `/api/github/connect` can't succeed (missing `refreshToken`) and duplicates OAuth | `GitHubAccountServiceImpl.java:45` |
| A12 | Medium | Generic `RuntimeException("User not found")` → 500; no catch-all, `AIServiceException`, or `AccessDenied` handlers | `UserServiceImpl.java:79,110`, `GlobalExceptionHandler` |
| A13 | Medium | No CORS configuration, so a browser frontend on another origin is blocked | `SecurityConfig.java` |
| A14 | Medium | 90 MB model in Git + image; region (`EU_NORTH_1`) and bucket hard-coded | `embeddings/`, `S3Config.java`, properties |
| A15 | Medium | `ddl-auto=update` locally + Flyway → schema drift; `vector_store` outside Flyway | `application.properties:7` |
| A16 | Medium | No pagination anywhere; PR list endpoints return all rows | controllers |
| A17 | Low | Dead `TestController`, unused `AIChatRequest/Response`, unused `GitHubTokenService` paths, `RedisServiceImpl.ping()` unused | — |
| A18 | Low | Package/directory mismatch for `S3Service` | `service/S3Service.java:1` |
| A19 | Low | `show-sql=true` locally; `health.show-details=always` publicly in docker profile | properties |
| A20 | Low | `HELP.md` is Initializr boilerplate (Boot 4.1.0 links vs 3.5.6 in pom); no README | `HELP.md`, `.dockerignore` excludes README |
| A21 | Info | Spring Boot 3.5.x open-source support window is ending/ended; Boot 4.x exists. Decide when to upgrade (D-09) | `pom.xml` |

## 7. What is genuinely solid

- Clean layering and naming conventions, constructor injection, and `@Transactional` boundaries on services.
- OAuth state design: an HMAC-signed, Redis-backed, single-use, 10-minute state with constant-time comparison (`GitHubOAuthStateServiceImpl`).
- JWT logout via Redis `jti` blacklist with TTL = remaining token life.
- GitHub sync is idempotent (upsert by `githubId`) and paginates the GitHub API.
- AI provider abstraction (`AIProvider` with mock/OpenAI switch) and a strict JSON prompt contract. The mock makes local development free.
- Dockerfile: multi-stage, dependency-layer caching, non-root runtime user, healthcheck.
- Flyway baseline exists, a good basis for versioned migrations.

## 8. Items needing manual verification or external access

- Whether the **AWS RDS instance** `devpulse-postgres` (eu-north-1) and S3 bucket still exist, and whether they're **accruing cost** after two idle months. Check the AWS Billing console.
- Whether the GitHub OAuth credentials belong to a **GitHub App** (expiring user tokens, which the code assumes) or an **OAuth App**.
- Whether the leaked JWT secret or local DB password is reused anywhere (RDS, other projects).
- Whether an OpenAI key was ever placed in a local, uncommitted `.env` (the committed value is a 7-char placeholder).
- That the project compiles. **Not verified**: there's no JDK on this machine. First action in M0.
