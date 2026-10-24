# Technical Decisions, Blockers & Risks

Each decision has a recommendation. Items marked **⚠ Needs approval** are ones I won't act on without your explicit yes.

## 1. Blockers (must clear before implementation)

| ID | Blocker | Resolution |
|---|---|---|
| B-1 | No JDK, Docker, Node, or Homebrew on this Mac. Nothing can be compiled, run, or tested | MAC_SETUP.md §2 (you run it or approve it) |
| B-2 | Literal JWT secret + local DB password published in a **public** repo | D-01 + M0-01 |
| B-3 | Local profile expects Postgres on `localhost:5433`, but compose no longer provides one; docker profile points at RDS | M0-03 / M0-04 |
| B-4 | No test harness, so "done" can't be proven | M0-07 |
| B-5 | GitHub credential type unknown (App vs OAuth App). It determines token handling, webhooks, and comment identity | D-02 (check in GitHub → Settings → Developer settings) |

## 2. Security concerns

| ID | Concern | Severity | Plan |
|---|---|---|---|
| S-1 | Leaked JWT signing secret means anyone can mint valid tokens for any deployment still using it | Critical | Rotate now (M0-01). Assume all past tokens are forged-able |
| S-2 | IDOR on repository/PR endpoints, including delete | Critical | M1-02 |
| S-3 | GitHub OAuth tokens may be serialized in API responses and in the Redis cache | Critical | M1-01, M1-05 |
| S-4 | GitHub tokens stored plaintext in DB | High | M1-09 (AES-GCM, KMS later) |
| S-5 | S3 test controller gives every user write/delete on the "prod" bucket | High | M1-08 |
| S-6 | RDS hostname and S3 bucket name are public; check that RDS isn't publicly accessible and its security group is tight | High | Manual AWS check (D-07) |
| S-7 | Webhook endpoint (future) is public; it must verify HMAC on the raw body | High | M5-01 |
| S-8 | Prompt injection via PR content (malicious code comments steering the LLM, e.g. "ignore issues") | Medium | Treat diff as data in prompt, validate output schema, never let the LLM trigger actions (M4-04) |
| S-9 | Sending private source code to a third-party LLM | Medium | Per-repo opt-in, provider data-retention settings, mock default (D-03) |
| S-10 | JWT in browser storage is XSS-exposed | Medium | D-05 |
| S-11 | `health.show-details=always` publicly | Low | M7-04 |

## 3. Decisions requiring your approval

### D-01 · Leaked secrets and Git history  ⚠ Needs approval
- **Facts:** the repo is public. `jwt.secret` (literal) and the local datasource password have been in every commit since `36edbdd`. The 90 MB ONNX model is in `ecba859`.
- **Options:**
  - (a) Rotate secrets and remove them from `HEAD` only. History keeps the old (now useless) values and the 90 MB blob.
  - (b) (a) plus rewrite history with `git filter-repo` to purge the secrets and `embeddings/`, then force-push `main`. Anyone else's clones break.
  - (c) (a) plus make the repo private.
- **Decided 2026-10-21:** option (a). CI's gitleaks job therefore scans the checked-out files, not full history; a history scan would fail permanently on the rotated secret. The repo's `.gitleaks.toml` adds a rule for literal values on secret-looking config keys, because gitleaks' built-in entropy rules did **not** detect the original leak (tested against `ecba859`).
- **Recommendation:** (a) now, mandatory. Add (b) as well, since you're the only contributor and the blob will burden every clone and CI run forever. Force-pushing is destructive, so I'll only do it on your explicit go-ahead, after making a backup branch/bundle. Rotation is what actually fixes the leak. A rewrite only cleans up.

### D-02 · GitHub App vs OAuth App  ⚠ Needs approval
- The code expects expiring user tokens with refresh tokens, which only **GitHub Apps** issue.
- **Recommendation: GitHub App.** Fine-grained repo permissions (Contents: read, Pull requests: read & write, Metadata), a built-in webhook with one secret, an installation token to post review comments as `devpulse-ai[bot]` rather than as the user, and per-repo installation. User login/identity still uses the App's user-to-server OAuth (existing flow).
- **Impact:** adds the installation id to the account/repos, an installation-token service (JWT signed with the App private key), and `installation` webhook events. A private key needs storing (D-07 secrets).
- If you keep an OAuth App: `repo` scope (broad), webhooks must be created per repo through the API, and comments post as the user.

### D-03 · LLM provider and spend  ⚠ Needs approval (paid)
- Current: `ai.provider=mock` (default) or `openai` via Spring AI. Model property `gpt-4o-mini`.
- **Recommendation:** keep the provider pluggable through Spring AI and keep mock as the default everywhere except an explicitly configured environment. Pick one real provider for M4-15 based on code-review quality and cost at that point. Spring AI supports OpenAI, Anthropic, Bedrock, and others, so switching later is a config change plus a starter dependency. Set a hard monthly spend cap in the provider console before adding any key.
- I won't make any paid API call without your explicit approval for that run.

### D-04 · Where the frontend lives
- **Options:** (a) `frontend/` folder in this repo (monorepo) · (b) a separate `devpulse-ai-frontend` repo.
- **Recommendation: (a).** One PR can change the API and its screen together (the vertical slices in ROADMAP), with one CI pipeline and shared docs. Keep the backend at the repo root (no move, so no Dockerfile or path churn). Optionally rename the GitHub repo to `devpulse-ai` later (GitHub redirects the old URL).

### D-05 · Browser session strategy
- Existing: stateless Bearer JWT (24 h), no refresh token, Redis blacklist on logout.
- **Recommendation for the MVP:** keep the Bearer JWT (no backend redesign). Store it in memory, mirrored to `sessionStorage` so a refresh survives, rather than in `localStorage`. Shorten expiry to 1–2 h. Add a strict CSP (M7-04).
- **Production upgrade (M7-06 decides):** short-lived access token plus an httpOnly, `SameSite=Strict` refresh cookie with a rotation endpoint.

### D-06 · Embeddings and vector store
- Existing: local ONNX all-MiniLM-style model (384-dim) via `spring-ai-starter-model-transformers` and PGVector.
- **Recommendation:** keep local ONNX embeddings (zero per-call cost, private code never leaves the server for indexing) and keep pgvector (one database, no new service). Load the model from a URI with a disk cache instead of the classpath (M0-05). Revisit with a code-specialized embedding model only if M6-10 recall is poor.
- **Watch:** the transformers starter downloads native ONNX Runtime/DJL tokenizer libraries at runtime. Verify on arm64 Mac and in the linux/amd64 container (M0-05/M0-07).
- **Outcome of M0-05 (2026-10-21):**
  - The committed model was a custom export that matches no public file. Its configured output (`sentence_embedding`, 2-D) crashed Spring AI 1.1.8 with a `ClassCastException`, so embeddings never worked.
  - Replaced it with `sentence-transformers/all-MiniLM-L6-v2` pinned at revision `1110a243fdf4706b3f48f1d95db1a4f5529b4d41` (model sha256 `6fd5d72f…6046452`), using output `last_hidden_state` with mean pooling.
  - Its vectors are identical (cosine 1.000000) to the old weights' `token_embeddings` output.
  - It downloads once into `EMBEDDING_CACHE_DIR`. Both tokenizers truncate at **128 tokens**, so M6 code chunks must fit in 128 tokens.
  - Spring AI doesn't verify checksums on download; the pinned revision is the integrity guard.

### D-07 · AWS footprint and cost  ⚠ Needs approval (cost)
- Existing references: RDS `devpulse-postgres` (eu-north-1) and S3 bucket `devpulseai-prod-storage-2026-001`. Current state and spend are unknown.
- **Immediate action for you:** open AWS Billing and Cost Explorer. If the RDS instance has run idle for two months, consider snapshotting and stopping or deleting it until M8.
- **Recommended target (M8):** ECS Fargate (1 small task) + ALB, RDS PostgreSQL 17 `db.t4g.micro` with pgvector, Redis (ElastiCache `cache.t4g.micro`, or Redis as a sidecar container for the lowest cost), S3 + CloudFront for the SPA, SSM Parameter Store for secrets, and IaC in Terraform. Budget alert first.
- Infra changes are applied only after you've reviewed a `plan`.

### D-08 · Keep S3?
- S3 has no product use today (only a test controller).
- **Recommendation:** remove the test controller (M1-08). Keep the `S3Service` code dormant, or delete it. Introduce S3 later only for a concrete need (e.g. exporting review reports). This removes an AWS dependency from local development.

### D-09 · Spring Boot version
- On 3.5.6. Boot 4.x is GA, and 3.5's open-source support window is ending or has ended.
- **Recommendation:** first move to the latest 3.5.x patch (low risk) in M0. Schedule a separate Boot 4 / Spring AI 2 upgrade after M4, when tests exist to catch regressions. Don't combine the upgrade with feature work.

### D-10 · Docker runtime on the Mac
- **Recommendation:** Docker Desktop (Testcontainers works with zero config; free for personal use). OrbStack is a lighter alternative but may need a Testcontainers host setting.

### D-11 · Frontend stack
- **Recommendation:** Vite + React + TypeScript (strict); React Router (data routers); TanStack Query for server state; a small Zustand store for auth/UI; Tailwind CSS + shadcn/ui (Radix) for accessible components; react-hook-form + zod; `openapi-typescript` for generated API types; Vitest + React Testing Library + MSW; Playwright for E2E; `react-diff-view` (or similar) for diffs; Recharts for dashboard charts.
- Rationale: mainstream and well documented, it fits a typed REST backend, and generated types keep FE/BE contracts in sync.

### D-12 · Background job mechanism
- **Recommendation:** start with a DB-backed job table (`reviews`, `repository_index_jobs` already carry status) plus a Spring `@Async` executor on Java 21 virtual threads, with a `SELECT … FOR UPDATE SKIP LOCKED` poller for recovery. That needs no new infrastructure and survives restarts. Move to SQS only if multiple instances or high volume demand it.

### D-13 · Review defaults
- **Recommendation:** per repo, `review_enabled=false` until the user opts in, `auto_review=true` once enabled, `post_comments=false` by default (users preview first), comment threshold `MEDIUM`, and skip draft PRs.

## 4. Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| LLM findings are noisy or hallucinated, with lines outside the diff | High | Users lose trust | Line validation (M4-04), confidence field, severity threshold, dismiss feedback |
| LLM cost overruns from large PRs or webhook storms | Medium | Money | Guardrails (M4-07), dedupe by head SHA (M5-02/03), spend caps |
| GitHub API rate limits during sync/index | Medium | Slow or failed syncs | Conditional requests, backoff, tarball instead of per-file fetch (M6-02) |
| ONNX native libs fail on arm64 or in slim JRE images | Medium | Embeddings broken | Verify early in M0-05/M0-07; fall back to an API embedding model (D-06) |
| Scope creep (frontend + AI + infra for one developer) | High | Stalled project | Vertical slices; the MVP cut-line is M0–M4 (manual review demo), and M5–M8 come after |
| Spring AI API changes on upgrade | Medium | Rework | Isolate behind `AIProvider` and a `CodeSearchService` interface |
| Prompt injection from PR content | Medium | Misleading reviews | S-8 mitigations; the LLM has no tools or write access |

## 5. Assumptions (correct me if wrong)

- You're the sole contributor, and nobody else depends on the current `main`.
- There's no production user data. Existing DB data (local or RDS) can be discarded or re-synced.
- The MVP target is a demo-able, portfolio-grade, deployed app, not multi-tenant SaaS scale.
