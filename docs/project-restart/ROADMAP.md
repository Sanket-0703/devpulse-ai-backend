# DevPulse AI — Full-Stack Roadmap

Built from the audit ([PROJECT_AUDIT.md](PROJECT_AUDIT.md)). Milestones are **vertical slices**: each one that adds a user-facing capability ends with both the backend API and the React screen wired together and verified end to end. Task details are in [TASK_BACKLOG.md](TASK_BACKLOG.md); open decisions are in [TECHNICAL_DECISIONS.md](TECHNICAL_DECISIONS.md).

## Target architecture

```
 Browser ── React SPA (Vite + TS) ──HTTPS/JSON (Bearer JWT)──► Spring Boot API ──► PostgreSQL 17 + pgvector
                                                                  │   │              (users, repos, PRs, reviews,
 GitHub ──OAuth redirect──► /api/github/oauth/callback ──302──► SPA   │               findings, vector_store)
 GitHub ──Webhooks (HMAC)─► /api/webhooks/github ──► job queue ───────┤
                                                                  │   └──► Redis (OAuth state, JWT blacklist,
 Review worker (async): PR diff ─► chunk ─► RAG context (pgvector) ─►       caches, rate limits)
   LLM via Spring AI (mock | OpenAI | other) ─► findings ─► DB ─► optional inline comments on GitHub
```

Principles:
- **The mock AI provider stays the default** in local and dev. Paid models only run when explicitly enabled (cost guardrail).
- **Flyway is the only schema authority.** Every milestone that adds tables ships a `V{n}__*.sql`.
- **No entity leaves a controller.** DTOs only, with ownership enforced in services.
- Keep the existing package layout (`controller / service / service.impl / repository / entity / dto / client`). The frontend lives in a new top-level `frontend/` folder (D-04).

## Dependency graph

```
M0 Recovery & local env
 └─► M1 Backend stabilization & security baseline
      ├─► M2 Frontend foundation + auth slice
      │    └─► M3 GitHub connect, repos & PRs slice ─┐
      │                                               ├─► M4 AI PR review slice ─► M5 Webhooks, auto-review, inline comments
      └───────────────────────────────────────────────┘                     └─► M6 Indexing, semantic search & RAG
                                                    M4..M6 ─► M7 Hardening & E2E ─► M8 Deploy, CI/CD, monitoring, docs
```
M0-08 (CI) starts in M0 and grows with every milestone. M6 can begin once M4-01 (schema) exists; it doesn't have to wait for M5.

---

## M0: Repository recovery & local development baseline  ★ start here
**Goal:** a secure, reproducible environment where `./mvnw verify` passes on this Mac and in CI, with no secrets in the repo.
- Install the toolchain (MAC_SETUP.md) and confirm the project compiles.
- Rotate the leaked JWT secret and remove every literal secret from tracked config.
- Re-create a local compose file with `pgvector/pgvector:pg17` and Redis. Fix the docker-profile host/env bugs.
- Move the 90 MB ONNX model out of Git and out of the image.
- Make Flyway authoritative (`ddl-auto=validate`).
- Replace `contextLoads` with a Testcontainers-backed integration test. Add a GitHub Actions build.

**Exit criteria:** fresh clone → `cp .env.example .env` → `docker compose -f compose.dev.yaml up -d` → `./mvnw verify` is green; the app starts and `/actuator/health` is UP; secret-scan finds no literal secrets in `HEAD`.

## M1: Backend stabilization & security baseline
**Goal:** the existing ~25 endpoints are safe, return DTOs, and fail with consistent JSON errors.
- DTOs for repositories and PRs; ownership checks everywhere (fix IDOR and token leakage).
- JWT filter returns a 401 JSON for bad, expired, or blacklisted tokens. Add JSON entry point and access-denied handlers. Catch-all exception handler.
- Fix the PR cache (DTO-based) and remove dead or unsafe code (`/api/github/connect`, inner `TestController`, S3 and vector test controllers).
- CORS for the SPA origin. Audit timestamps. Encrypt GitHub tokens at rest (V2 migration).
- Tests: auth flow, ownership, error contract.

**Exit:** a security test suite proves user A can't read or delete user B's data, and that no response body contains token fields.

## M2: Frontend foundation + authentication slice
**Goal:** a professional SPA shell where users can register, log in, see their profile, and log out against the real backend.
- Backend: `GET /api/auth/me`, JSON register response, OpenAPI docs (springdoc) to drive the typed API client.
- Frontend: Vite + React + TypeScript scaffold, routing, design system and app shell, API client + auth store, protected routes, auth screens, profile/password settings, loading/error/empty patterns.

**Exit:** Playwright test: register → login → see dashboard shell → update profile → logout → protected route redirects to login.

## M3: GitHub connection, repositories & pull requests slice
**Goal:** a user connects GitHub, sees their repositories, opts repos into DevPulse, and browses PRs.
- Decide GitHub App vs OAuth App (D-02). Fix OAuth URL, scopes, and token handling. Callback 302s to the SPA.
- Async initial sync (no work in the callback request), manual resync, connection status, and disconnect with token revocation.
- Paged repo list, repo toggle (`review_enabled`), and paged PR list/detail scoped to the owner.
- Frontend: connect-GitHub onboarding, repositories list/detail, PR list/detail.

**Exit:** with a real GitHub test account: connect → repos appear → enable one → its PRs are listed → disconnect clears everything.

## M4: AI review engine (manual PR review) slice
**Goal:** a user clicks "Run review" on a PR and gets persisted, line-anchored findings, including security findings.
- Schema V3: `reviews`, `review_findings`, PR `head_sha`/`base_sha`.
- Fetch PR files and patches from GitHub. Diff parsing and chunking with line mapping. Prompt v2 with file/line anchors and CWE.
- Async review job (QUEUED→RUNNING→COMPLETED/FAILED) with status polling. Provider config with mock default. Token/file budget guardrails.
- Deterministic vulnerability rules (secrets, obvious injection patterns) merged with LLM findings.
- Review history, finding dismissal, dashboard summary, Playground endpoint.
- Frontend: PR "Run review" + progress, review report (filters, diff view with findings), review history, dashboard, playground.

**Exit:** with the mock provider, the E2E flow produces findings shown inline on the PR diff. With a real provider (opt-in, budget-capped), one manual review on a sample PR succeeds.

## M5: Webhooks, automated reviews & inline GitHub comments
**Goal:** opening or updating a PR on an enabled repo triggers a review automatically, and results can be posted as inline PR comments.
- `POST /api/webhooks/github` with HMAC SHA-256 verification, delivery idempotency table, and event routing.
- `pull_request` opened/synchronize/reopened → PR upsert → review job (deduped by head SHA).
- Publish findings as a GitHub pull-request review with inline comments, respecting a severity threshold.
- User/repo review settings. Frontend settings UI and "Publish to GitHub" action.

**Exit:** on a test repo (via a smee.io/ngrok tunnel locally), pushing to a PR creates a review and inline comments appear on GitHub.

## M6: Codebase indexing, semantic search & RAG
**Goal:** repositories are indexed into pgvector; users can search code semantically, and reviews use retrieved context.
- Flyway-managed `vector_store` + index job table. Fetch the repo snapshot (tarball) for the default branch. Filter files and chunk them (language-aware, with line ranges).
- Embed with the local ONNX model (no API cost). Metadata: repo, path, lines, sha.
- `POST /api/search`, index status API. Incremental reindex on `push` webhook.
- Inject the top-k related chunks into the review prompt (RAG) and measure the effect.
- Frontend: index status/controls and the semantic search page with code previews.

**Exit:** searching "where are JWT tokens validated" in DevPulse's own repo returns `JwtAuthenticationFilter`/`JwtService` in the top 3. Reviews show "context used" references.

## M7: Hardening, end-to-end testing & error handling
- Playwright E2E suite for all core journeys against compose + mock AI + a WireMock GitHub stub.
- Rate limiting (auth, review triggers), request size limits, and input validation review.
- Security review: OWASP ASVS L1 checklist, dependency scanning, secret scanning in CI, CSP/security headers.
- Resilience: GitHub API timeouts/retries/rate-limit handling, LLM timeouts, job retry/dead-letter.
- Accessibility and responsive QA for the frontend.

## M8: Deployment, CI/CD, monitoring & documentation
- Production profile: no hard-coded hosts, secrets from AWS SSM/Secrets Manager.
- AWS (D-07): RDS PostgreSQL with pgvector, ElastiCache or a self-hosted Redis, ECS Fargate (or App Runner) for the API, S3 + CloudFront for the SPA, ALB + ACM TLS.
- GitHub Actions: build/test → image to ECR → deploy. Frontend build → S3 sync → CloudFront invalidation. Environment protections.
- Monitoring: Actuator + Micrometer → CloudWatch, structured JSON logs, alarms, budget alerts.
- Docs: README, architecture, API (OpenAPI), runbook, ADRs.

---

## Single best first milestone: M0

**Why M0 first:** nothing can be verified until the project compiles and runs here. The public leak of the JWT secret is an active security issue. And every later milestone needs a working local Postgres+pgvector+Redis and a test harness. M0 changes no product behaviour, so it carries the least risk.

**How to begin (in order, each a separate small PR):**
1. **You:** install Homebrew, Temurin 21, Docker Desktop, fnm/Node LTS, and the CLI clients (MAC_SETUP.md §2). Set your git identity. Check AWS Billing for the old RDS/S3 resources.
2. **M0-02:** run `./mvnw -q -DskipTests compile` and record the result. Fix only compile blockers.
3. **M0-01:** move every secret in `application.properties` to `${ENV}` placeholders with no defaults, and generate a new JWT secret locally. Decide D-01 (history rewrite or not).
4. **M0-03 / M0-04:** add `compose.dev.yaml` (pgvector + Redis), a `local` profile, and fix the docker profile env mapping.
5. **M0-05 → M0-08:** externalize the ONNX model, make Flyway authoritative, add the Testcontainers integration test, add CI.

Expected effort for M0: about 1.5–2.5 focused days, including tool installs.
