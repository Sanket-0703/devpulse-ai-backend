# Build Plan — Local, Feature by Feature

Updated **2026-10-22**.

Ground rules
- **Local only.** Everything runs on this Mac (Docker for Postgres + Redis, backend via `./mvnw`, frontend via the dev server). No cloud hosting until the whole app works locally.
- **Level 0.** We treat the project as unbuilt. Existing backend code is reused as a starting point, but a feature is only `[x]` once it has been rebuilt or reviewed, tested, and used from the frontend.
- **Every feature is full-stack.** Each level ships the backend API *and* its screens together, and ends with a check you do in the browser.
- **Mock AI by default.** No paid AI calls unless you approve them. OpenAI is the real provider (per the resume).
- **Tests as we go.** JUnit + Mockito for every backend feature, with a JaCoCo gate building up to **80%+ coverage**. Every screen gets component tests (Vitest + React Testing Library). GitHub calls are tested against WireMock stubs, never the real GitHub.
- **Learn the IDEs on the way.** IntelliJ IDEA for the backend, VS Code for the frontend. Each level teaches the IDE features that level needs; the running guide is [IDE_GUIDE.md](IDE_GUIDE.md).
- **One branch and one PR per level.** Each level starts on a fresh branch from `main` named `level-N-<topic>`, is committed as backend / frontend / docs commits, and goes up as a draft PR so CI runs. You review and merge it.
- **Every level ends with its own explainer.** `docs/levels/LEVEL_N.md` (and PDF) walks through every page, class and function built in that level, how the requests flow, and why it was built that way. Level docs: [Level 1](../levels/LEVEL_1.md) · [Level 2](../levels/LEVEL_2.md) · [Level 3](../levels/LEVEL_3.md) · [Level 4](../levels/LEVEL_4.md).
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

## Level 2 — Authentication ✅ (IDE debugging exercises are yours to do)

- [x] BE: register (201 JSON), login, logout (refresh token revoked + access token blacklisted in Redis), `GET /api/auth/me`
- [x] BE: 15-min access token (JWT, user id as subject) + 7-day refresh token in an httpOnly `SameSite=Strict` cookie; rotated on every refresh, stored only as a SHA-256 hash (`refresh_tokens`, Flyway V3); reuse of an old token revokes its whole family
- [x] BE: bad / expired / forged / logged-out token → JSON 401 with a reason (never 500)
- [x] BE: case-insensitive emails, bcrypt 72-byte password cap, same response time for unknown emails and wrong passwords
- [x] BE: tests — 24 unit (JUnit + Mockito) + 17 integration; 50 backend tests total, coverage 27% → 42%
- [x] FE: Register and Login pages (react-hook-form + zod) with field and server errors (duplicate email, wrong password), show/hide password
- [x] FE: auth state, protected routes, return to the requested page after sign-in, user menu + sign out
- [x] FE: access token in memory only; session restored from the cookie on reload; silent refresh + retry on 401, shared across requests and tabs (Web Locks)
- [x] FE: 16 new tests (22 total) against an in-memory MSW auth backend
- [ ] IDE: debugging — breakpoints in IntelliJ (step through login) and in VS Code / browser dev tools ([IDE_GUIDE.md](IDE_GUIDE.md), Level 2)

**Check:** register → login → land on the dashboard → refresh keeps you logged in → logout → protected page sends you back to login. ✅ Done in the browser against the real backend.

## Level 3 — Profile & settings ✅ (IDE refactoring exercises are yours to do)

- [x] BE: `GET/PUT /api/profile` (name + email; changing the email needs the current password), `PUT /api/profile/password`, `POST /api/auth/logout-all`
- [x] BE: password change and "sign out everywhere" end other sessions immediately — refresh tokens revoked **and** older access tokens rejected via a per-user cutoff (`users.tokens_valid_after`, Flyway V4); the device that changed the password gets a fresh session
- [x] BE: wrong current password = 400 field error (not 401); audit timestamps (`created_at`/`updated_at`) filled automatically and stored in UTC
- [x] BE: old `UserService`/`ProfileResponse` replaced by `ProfileService` + `UserResponse`; tests — 73 backend tests, coverage 42% → 47%
- [x] FE: Settings with Profile and Security tabs; "Member since"; save only when changed; password field appears only when the email changes; confirm dialog for "Sign out everywhere"
- [x] FE: shared server-field-error helper; 11 new tests (33 total)
- [x] Level doc: [LEVEL_3.md](../levels/LEVEL_3.md)
- [ ] IDE: refactoring tools and Git history in IntelliJ and VS Code ([IDE_GUIDE.md](IDE_GUIDE.md), Level 3)

**Check:** change name and password in the browser, log in again with the new password. ✅ Done against the real backend — plus a second device (signed in from the terminal) was signed out by the password change, and "Sign out everywhere" ended the browser session.

## Level 4 — Connect GitHub (code done; real-GitHub check pending)

Decision: **GitHub App** (fine-grained permissions, per-repo install, expiring tokens, built-in webhook, bot comments). Setup: [GITHUB_APP_SETUP.md](../GITHUB_APP_SETUP.md).
- [x] BE: "Sign in with GitHub" — creates the account or signs into the linked one; **never auto-links by email** (DevPulse doesn't verify emails: pre-account-takeover)
- [x] BE: Connect flow bound to the signed-in user, install flow, one callback that always redirects to the frontend, connection status, disconnect (revokes the grant on GitHub; refused for GitHub-only accounts)
- [x] BE: single-use random OAuth `state` in Redis (GETDEL); GitHub calls outside DB transactions, with timeouts
- [x] BE: GitHub tokens AES-256-GCM encrypted at rest (JPA converter, Flyway V5), never in API responses
- [x] BE: GitHub-only accounts (no password) can set a first password; WireMock stubs of GitHub; 101 backend tests, coverage 47% → 70%
- [x] FE: "Continue with GitHub" on sign-in/sign-up, `/github/callback` page, Settings → GitHub (connect, install, manage, disconnect), "Set a password" for GitHub-only accounts; 46 frontend tests
- [x] Level doc: [LEVEL_4.md](../levels/LEVEL_4.md)
- [ ] IDE: HTTP Client, Database tool, DevTools redirect chain ([IDE_GUIDE.md](IDE_GUIDE.md), Level 4)
- [ ] Real GitHub: register the app ([GITHUB_APP_SETUP.md](../GITHUB_APP_SETUP.md)), then sign in with GitHub, connect, install, disconnect against github.com

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
