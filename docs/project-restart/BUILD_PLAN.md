# Build Plan — Local, Feature by Feature

Updated **2026-10-22**.

Ground rules
- **Local only.** Everything runs on this Mac (Docker for Postgres + Redis, backend via `./mvnw`, frontend via the dev server). No cloud hosting until the whole app works locally.
- **Level 0.** We treat the project as unbuilt. Existing backend code is reused as a starting point, but a feature is only `[x]` once it has been rebuilt or reviewed, tested, and used from the frontend.
- **Every feature is full-stack.** Each level ships the backend API *and* its screens together, and ends with a check you do in the browser.
- **Mock AI by default.** No paid AI calls unless you approve them. OpenAI is the real provider (per the resume).
- **Tests as we go.** JUnit + Mockito for every backend feature, with a JaCoCo gate building up to **80%+ coverage**. Every screen gets component tests (Vitest + React Testing Library). GitHub calls are tested against WireMock stubs, never the real GitHub.
- **Learn the IDEs on the way.** IntelliJ IDEA for the backend, VS Code for the frontend. Each level teaches the IDE features that level needs; the running guide is [IDE_GUIDE.md](IDE_GUIDE.md).
- **Supported languages** for reviews, security rules and code search: **Java, JavaScript/TypeScript, Python**. Other files are skipped.

## Tech stack (from the resume)

| Layer | Stack |
|---|---|
| Frontend | **React** + TypeScript on Vite · React Router · TanStack Query (server data) · Tailwind CSS + shadcn/ui · react-hook-form + zod · Vitest + React Testing Library · API types generated from OpenAPI (`openapi-typescript`) |
| Backend | Java 21, Spring Boot, Spring Security (JWT + GitHub OAuth2), Spring AI |
| Data | PostgreSQL + pgvector (repositories, reviews, findings, embeddings), Redis (caching API responses and embeddings, sessions) |
| AI | OpenAI via Spring AI (mock provider for local development) |
| Tooling | Docker Compose, GitHub Actions CI, JUnit + Mockito + JaCoCo, WireMock |
| IDEs | IntelliJ IDEA (backend), VS Code (frontend) |

Resume claims and where each gets built:

| Resume claim | Level |
|---|---|
| OAuth2 authentication | 2 (email login + refresh tokens), 4 ("Sign in with GitHub" + GitHub connection) |
| Normalized PostgreSQL schemas for repositories, reviews, findings | 5, 7 |
| Autonomous AI code review + vulnerability detection | 7 |
| GitHub webhook ingestion, inline PR comments posted automatically | 9 |
| Spring AI + vector DB: chunk and embed source files, retrieve context per diff (RAG) | 10 |
| Semantic codebase search | 10 |
| Redis caching of embeddings and API responses | 5, 6 (API responses), 10 (embeddings) |
| Docker | Level 0 (Postgres + Redis), Level 11 (full app in Compose) |
| GitHub Actions CI with 80%+ coverage (JUnit, Mockito) | Every level; gate enforced in Level 11 |
| AWS + CD | After Level 11, separately |

Legend: `[x]` done · `[ ]` to do · **BE** backend · **FE** frontend

---

## Level 0 — Development environment ✅

- [x] Homebrew 7.0.10
- [x] Java 21 (Homebrew `openjdk@21`, `JAVA_HOME` set in `~/.zprofile`)
- [x] Node 24 LTS + npm 11 (via fnm, set up in `~/.zshrc`)
- [x] `psql`, `redis-cli`, `gh`; Docker Desktop running
- [x] Postgres (pgvector) + Redis up from `compose.dev.yaml`
- [x] `./mvnw clean verify` green (3 integration tests)
- [x] Backend starts locally; `/actuator/health` UP; register + login respond
- [x] Frontend tech stack confirmed from your resume (React)
- [ ] `gh auth login` (only needed when we push / open PRs)

## Level 1 — Project skeletons + app shell ✅ (IDE setup is yours to do)

- [x] BE: upgrade to the latest Spring Boot 3.5.x patch (3.5.16) (Boot 4 / Spring AI 2 is a separate step after Level 7)
- [x] BE: JaCoCo coverage report (baseline 27%); API docs (Swagger UI + OpenAPI JSON)
- [x] BE: CORS for `localhost:5173`, consistent JSON error format (incl. JSON 401/403)
- [x] FE: scaffold `frontend/` (React + TypeScript + Vite, Tailwind + shadcn/ui), lint/format/test set up, dev server on `localhost:5173`
- [x] FE: app layout (sidebar, top bar, light/dark), base UI components, 404 page
- [x] FE: typed API client — TypeScript types generated from the backend's OpenAPI docs
- [x] CI runs frontend lint/typecheck/test/build next to the backend job
- [x] One guide to start everything locally ([LOCAL_DEV.md](../LOCAL_DEV.md))
- [ ] IDE: open the backend in IntelliJ (JDK, run config, `.env`), the frontend in VS Code (recommended extensions, format on save)

**Check:** open `localhost:5173`, see the empty app shell; the browser can call the backend without CORS errors.

## Level 2 — Authentication

- [ ] BE: register (201 JSON), login, logout (token blacklist), `GET /api/auth/me`
- [ ] BE: short-lived access token + refresh token in a secure httpOnly cookie, with rotation on refresh
- [ ] BE: bad / expired / logged-out token → clean JSON 401 (never 500)
- [ ] BE: tests for each of the above
- [ ] FE: Register and Login pages with validation and server errors (duplicate email, wrong password)
- [ ] FE: auth state, protected routes, logout, silent token refresh, redirect to login when the session ends
- [ ] IDE: debugging — breakpoints in IntelliJ (step through login) and in VS Code / browser dev tools

**Check:** register → login → land on the dashboard → refresh keeps you logged in → logout → protected page sends you back to login.

## Level 3 — Profile & settings

- [ ] BE: get/update profile, change password (+ tests)
- [ ] FE: Settings → Profile and Security pages

**Check:** change name and password in the browser, log in again with the new password.

## Level 4 — Connect GitHub

Decision first: GitHub App vs OAuth App.
- [ ] BE: "Sign in with GitHub" (log in or sign up with a GitHub account, linked to the same user if the email matches)
- [ ] BE: OAuth connect flow, callback redirects back to the frontend, connection status, disconnect
- [ ] BE: encrypt GitHub tokens in the database; no tokens in any API response
- [ ] FE: "Sign in with GitHub" button on Login/Register, "Connect GitHub" onboarding, callback page, GitHub section in Settings
- [ ] BE: WireMock stubs of the GitHub API for tests

**Check:** sign in with GitHub; connect your GitHub test account, see it as connected, disconnect.

## Level 5 — Repositories

- [ ] BE: background repo sync + manual resync, paged "my repositories" list, enable/disable review per repo, ownership checks
- [ ] FE: repositories list (search, filter, pagination) and repository detail page

**Check:** your repos appear; turn review on for one.

## Level 6 — Pull requests

- [ ] BE: PR sync, paged PR list per repo, PR detail, changed files + diff
- [ ] FE: PR list (open / closed / merged) and PR detail with the diff

**Check:** open a repo, browse its PRs, view a PR's changed files.

## Level 7 — AI code review (mock AI first)

- [ ] BE: review tables, diff parsing + chunking, review prompt, background review job with status, findings saved
- [ ] BE: security rules (hard-coded secrets, SQL injection patterns, etc.) merged with AI findings; cost limits
- [ ] BE: AI usage tracking — tokens and estimated cost per review and per user
- [ ] BE: demo mode — a seeded demo user with a sample repo, PR and finished mock review, so the app can be shown without GitHub or OpenAI
- [ ] FE: "Run AI review" with progress, review report (findings inline on the diff, filters, dismiss), review history
- [ ] Optional, with your approval: one run with a real AI provider

**Check:** run a review on a PR and see findings on the right lines of the diff.

## Level 8 — Dashboard & Playground

- [ ] BE: dashboard summary API; hardened "paste code → review" endpoint
- [ ] FE: dashboard (stats, severity chart, recent reviews, AI usage and cost, onboarding checklist); Playground page

## Level 8.5 — Framework upgrade

- [ ] Upgrade to Spring Boot 4 / Spring AI 2 on its own branch, with no feature work mixed in; the test suite from Levels 1–8 catches breakage

## Level 9 — Webhooks & GitHub comments

- [ ] BE: webhook endpoint with signature check, duplicate-delivery protection, auto-review when a PR opens or updates
- [ ] BE: publish findings as inline comments on the GitHub PR
- [ ] FE: review settings page, "Publish to GitHub" button
- [ ] Local tunnel guide (smee.io / ngrok) so GitHub can reach your Mac

**Check:** push to a PR on a test repo → review runs → comments appear on GitHub.

## Level 10 — Code indexing, semantic search & RAG

- [ ] BE: index a repo into pgvector (local embeddings, no API cost), index status, semantic search API, related code fed into reviews
- [ ] BE: cache embeddings in Redis (keyed by content hash) so unchanged code isn't re-embedded
- [ ] FE: Index tab on the repo page, Search page

**Check:** search "where are JWT tokens validated" in DevPulse's own repo → `JwtAuthenticationFilter` / `JwtService` in the top 3.

## Level 11 — Polish & hardening (still local)

- [ ] End-to-end browser tests (Playwright) for every journey above
- [ ] Rate limiting, input size limits, timeouts and retries for GitHub and the AI provider
- [ ] Security headers and a security review pass
- [ ] Accessibility and mobile-width check
- [ ] Whole app (frontend + backend + Postgres + Redis) runs with one `docker compose up`
- [ ] CI enforces the 80% backend coverage gate (JaCoCo)
- [ ] README with a local quick start
- [ ] Portfolio docs: architecture diagram, screenshots, a short demo GIF, and a "design decisions" page

## Optional extras (after Level 11, pick any)

- [ ] "Fixed since last review": when a PR is reviewed again, mark earlier findings as resolved
- [ ] Live progress with server-sent events instead of polling
- [ ] Password reset by email (Mailpit in Docker catches emails locally)
- [ ] Local monitoring: Prometheus + Grafana in Docker Compose

---

**After that** (separate discussion): hosting and deployment.
