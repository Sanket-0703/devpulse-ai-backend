# Level 8 — Dashboard & Playground

**Goal:** a home page that shows where things stand at a glance, and a safe "paste code → review" page that uses exactly the same review engine as pull requests.

**What you can do at the end of Level 8:**
- **Dashboard** (`/dashboard`, the page after sign-in):
  - a **Get started** checklist until all five steps are done (connect GitHub → install the app → sync → turn on AI review → run a first review), each step linking to where it's done;
  - **totals**: repositories, AI review on, open pull requests, reviews in the last 30 days;
  - **open findings by severity**, as a bar chart;
  - the **5 most recent reviews**, linking to their pull requests;
  - **AI usage** against the daily limits.
- **Playground** (`/playground`):
  - pick a language, paste up to 20,000 characters (or **Load an example**), press **Review**;
  - findings appear **under their line** of the snippet, from the same security rules and AI model as pull request reviews;
  - your code is **not stored**.
- **Usage** now includes Playground runs (runs today, out of 30) and counts them in the shared daily dollar budget.

> Builds on [LEVEL_7.md](LEVEL_7.md) (the review pipeline, limits, usage).

---

## 1. The big ideas

### One review engine, two entry points

Level 7's job had the review logic inside `ReviewRunner`. Level 8 moves it into **`ReviewEngine`**, which knows nothing about databases or GitHub:

```
                       ┌──────────────── ReviewEngine.analyze(files, maxChunks, budget) ───────────────┐
 ReviewRunner          │  plan (skip generated, parse, chunk) → security rules → AI per chunk          │
 (pull requests) ──────►  → validate + merge findings → tokens + estimated cost                        │
 PlaygroundService ────►                                                                               │
 (snippets)            └───────────────────────────────────────────────────────────────────────────────┘
```

The Playground turns a snippet into a **new file** in a diff where every line is added:

```
snippet.java                    @@ -0,0 +1,3 @@
  1  public void f() {    ──►    +public void f() {
  2    eval(x);                  +  eval(x);
  3  }                           +}
```

Line 2 of the snippet is line 2 of the "file". So the rules, the prompt, the mock AI, the validation and the frontend's diff view all work unchanged, and a finding on line 2 shows under line 2.

### Hardening the Playground

The old `POST /api/ai/review` took the raw request body as a string, with no limits, and sent it straight to the AI provider. It's **deleted**. The new `POST /api/playground/review` adds layers:

| Layer | What it does | On failure |
|---|---|---|
| Authentication | signed-in users only | 401 |
| Typed request | `{language, code}`; language must be a known value | 400 |
| Size | code is 1–20,000 characters (about 5,000 tokens) | 400 with the field message |
| Burst limit | 5 runs per minute per user (Redis counter) | 429 + `Retry-After` |
| Daily limit | 30 runs per user per UTC day | 429 until midnight UTC |
| Shared budget | reviews + Playground together under $1/day | 429 |
| Per-run cap | at most 2 AI requests, stops at $0.25 | result marked truncated |
| Prompt injection | the diff is declared untrusted data in the prompt | (no effect) |
| Output validation | the same checks as reviews (lines must exist, known severities…) | bad findings dropped or corrected |
| Privacy | only numbers are stored (language, size, tokens, cost), **never the code** | — |

### A fixed-window rate limit in Redis

```
INCR ratelimit:playground:{userId}        → 1, 2, 3 …
(on the first INCR) EXPIRE … 60 seconds   → the window starts with the first run
count > 5 → 429, Retry-After = the key's remaining TTL
```

- `INCR` is atomic, so two simultaneous requests can't both read "4".
- The key disappears by itself when the minute is over.
- It's a *fixed* window: someone could make 5 runs at 0:59 and 5 more at 1:01. That's acceptable here; the daily limit caps the total anyway. A sliding window (a sorted set of timestamps) or a token bucket would smooth that out, at the cost of more Redis work.

### One budget for everything

`AIUsageService` is the single place that knows what a user spent: **reviews and Playground runs added together**. The review start and the Playground both call `enforceDailyBudget`; the usage card and the dashboard read `usage()`. Before Level 8 the budget lived inside `ReviewServiceImpl`, so a second AI feature would have had its own, separate budget.

### "Open findings" on the dashboard

Adding up the findings of *every* review would count the same problem once per re-review. The dashboard counts only **each pull request's latest successful review**:

```sql
… where r.status = SUCCEEDED
  and r.createdAt = (select max(latest.createdAt) from Review latest
                     where latest.pullRequest = r.pullRequest and latest.status = SUCCEEDED)
```

That's a **correlated subquery**: for each review it asks "is this the newest successful one of its pull request?". The test reviews the same pull request twice and checks the totals equal one review's findings, not two.

---

## 2. Database: `V9__playground_runs.sql`

**`playground_runs`**: one row per Playground review.
- `user_id`, `language`, `code_chars`, `findings`;
- `provider`, `model`, `prompt_tokens`, `completion_tokens`, `estimated_cost_usd`;
- timestamps.

There is no column for the code. The index `(user_id, created_at DESC)` serves "runs and cost today" per user.

---

## 3. Backend, class by class

### 3.1 `service/impl/ReviewEngine` (extracted)

- **`analyze(files, maxChunks, budgetUsd)`** returns **`Analysis(plan, findings, chunksSent, truncated, promptTokens, completionTokens, cost)`**.
- It is the loop from Level 7: the plan, the rules, one AI request per chunk while the cost is under the budget, then validate and merge.
- `provider()` and `model()` say which AI is answering.
- **`ReviewRunner`** now fetches the files, calls `engine.analyze(files, review.maxChunks, limits.maxReviewCostUsd)`, adds "GitHub listed only part of the files" to `truncated`, and saves. Its behaviour is unchanged, and its tests still pass with a real `ReviewEngine` around the mocked provider.

### 3.2 `service/AIUsageService` + impl (new)

- **`enforceDailyBudget(userId)`**: today's review cost plus today's Playground cost ≥ `ai.limits.daily-cost-usd` → `TooManyRequestsException` with `Retry-After` until midnight UTC.
- **`usage(userId)`** returns `AIUsageResponse` with:
  - provider and model;
  - reviews today out of the limit, **Playground runs today out of the limit** (new);
  - cost today (both kinds) out of the budget;
  - reviews this month, tokens this month (both), cost this month (both).
- `startOfToday()` and `secondsUntilTomorrow()` (UTC), shared by every limit.
- **`ReviewServiceImpl`** keeps its own "reviews per day" check and asks `AIUsageService` for the budget. Its `usage()` moved here, and `GET /api/reviews/usage` is now served by `AIUsageService`.

### 3.3 The Playground

- **`entity/PlaygroundLanguage`**: JAVA, KOTLIN, JAVASCRIPT, TYPESCRIPT, PYTHON, GO, SQL, OTHER. `fileName()` gives "snippet.java", "snippet.py" … The extension helps the model and keeps the rules' generated-file filter happy.
- **`entity/PlaygroundRun`** + **`PlaygroundRunRepository`**: `countByUserIdAndCreatedAtGreaterThanEqual`, `sumCostSince` and `sumTokensSince`.
- **`dto/playground/`**:
  - **`PlaygroundRequest`**: `@NotNull language`, and `@NotBlank @Size(max = 20000) code`. `MAX_CODE_CHARS` is a constant, so the limit and its error message can't drift apart.
  - **`PlaygroundFinding`**: a finding without id or file (nothing is stored), with `line` 1-based in the snippet.
  - **`PlaygroundResponse`**: findings, provider, model, tokens, cost, `truncated`.
- **`PlaygroundServiceImpl.review(userId, request)`**:
  1. **`enforceLimits`**: the Redis burst counter (`RedisService.increment`), then today's run count, then the shared budget, cheapest check first.
  2. **`asNewFile(fileName, code)`** builds the all-added diff (Windows line endings normalized).
  3. `engine.analyze(…, MAX_CHUNKS = 2, maxReviewCostUsd)`.
  4. Saves a `PlaygroundRun` with numbers only, and returns the findings.
- **`controller/PlaygroundController`**: `POST /api/playground/review` with `@Valid`.
- **`RedisService.increment(key, ttl)`**: `INCR`, plus `EXPIRE` when the value is 1 (the start of the window).
- **Deleted:** `AIController`, `AIService(+Impl)`, and the unused `AIChatRequest` / `AIChatResponse`.

### 3.4 The dashboard

**`dto/dashboard/DashboardResponse`** has five parts:
- `Onboarding`: five booleans;
- `Stats`: five counts;
- `openFindings`: `FindingCounts`;
- `recentReviews`: `ReviewResponse` list;
- `usage`: `AIUsageResponse`.

**`DashboardServiceImpl.summary(userId)`** (read-only transaction) runs a handful of COUNT/SUM queries and never loads rows one by one:

| Field | Query |
|---|---|
| GitHub connected / app installed | `accountRepository.findByUserId` |
| repositories, AI review on | `countByGithubAccountUserId`, `…AndReviewEnabledTrue` (derived queries) |
| open pull requests | `countByGithubRepositoryGithubAccountUserIdAndState(userId, OPEN)` |
| reviews total / last 30 days | `countByUserId`, `countByUserIdAndCreatedAtGreaterThanEqual` |
| open findings | `sumOpenFindingsOfLatestReviews`: the correlated subquery above, four `coalesce(sum(...), 0)` |
| recent reviews | `findTop5ByUserIdOrderByCreatedAtDesc` with an `@EntityGraph` for the PR and repository |
| usage | `AIUsageService.usage` |

The long derived query names are Spring Data walking the relations: `GithubRepository → GithubAccount → User → id`.

**`controller/DashboardController`**: `GET /api/dashboard`.

### 3.5 Configuration

```properties
ai.limits.daily-playground-runs=30
ai.limits.playground-runs-per-minute=5
```

These are two new fields of `AIProperties.Limits`, with `@DefaultValue` so they're optional.

### 3.6 Backend tests

- **`PlaygroundIntegrationTest`** (3):
  - a snippet gets the SQL rule on line 3 and the mock's "Debug output" on line 2; cost is 0; usage shows 1 run today out of 30, and the tokens add up;
  - invalid input → 400 (blank, 20,001 characters, an unknown language), no token → 401, and the old `/api/ai/review` → 404;
  - the 6th run within a minute → 429 with `Retry-After` and a readable message. It uses the JDK HttpClient, because the test client would silently retry after the delay.
- **`DashboardIntegrationTest`** (2):
  - a new account: every onboarding step false, all counts 0;
  - after a sync, AI review on, and **two reviews of the same pull request**: all five steps true, stats `(1, 1, 1, 2, 2)`, open findings = **one** review's (1 high, 1 low), 2 recent reviews.
- **`PlaygroundServiceImplTest`**: the snippet-to-diff conversion and its line numbers.
- **Updated:**
  - `ReviewRunnerTest` wraps a real `ReviewEngine` around the mocked provider;
  - `ReviewServiceImplTest` now checks that the budget adds up reviews ($0.60) and Playground runs ($0.40);
  - `BackendApplicationIntegrationTest` expects migrations 1–9.

**Totals:** 185 backend tests; `mvn verify` exits 0 on a clean build; instruction coverage **93.4%**.

---

## 4. Frontend, file by file

### 4.1 `features/dashboard/api.ts`

`getDashboard()` and **`useDashboard()`**. The hook uses `staleTime: 0`, so the dashboard is fetched fresh every time you open it: it summarizes things that change on other pages.

### 4.2 `features/dashboard/components.tsx`

- **`OnboardingChecklist`**:
  - five steps with "n of 5 steps done";
  - done steps are struck through with a green check; open steps are links to where you do them;
  - screen readers hear "Done:" / "To do:".
- **`StatCards`**: four numbers in a "Totals" region.
- **`SeverityChart`**:
  - horizontal bars scaled to the largest count, colour-coded like the severity badges;
  - built with plain `div`s, no chart library: four bars don't justify a dependency;
  - it's a `<dl>`, so each severity and its number are read together, and the number is always printed (the chart doesn't rely on colour or length).

### 4.3 `features/dashboard/pages/DashboardPage.tsx`

- Loading skeleton, error with Retry, then **`DashboardContent`**:
  - the checklist (hidden once every step is done);
  - totals;
  - chart and **`RecentReviews`** side by side on wide screens;
  - **AI usage**.
- `RecentReviews` shows the counts for a finished review, or its status while it runs, and links to the PR and to all reviews.

### 4.4 `features/reviews/UsageCards.tsx` (moved, extended)

The usage cards moved out of the Reviews page because the dashboard shows them too. They gained **Playground today: n of 30 runs**.

### 4.5 `features/playground/api.ts`

- `LANGUAGES` (value and label), `MAX_CODE_CHARS = 20_000`.
- **`EXAMPLE`**: a Java method with debug output, SQL concatenation and an empty catch.
- `reviewSnippet()`.

### 4.6 `features/playground/pages/PlaygroundPage.tsx`

- **Form:**
  - a language select and **Load an example**;
  - a monospace textarea, with a live counter "8 / 20,000 characters" that turns red (and sets `aria-invalid`) past the limit;
  - **Review** is disabled while it runs, for empty code, or for code over the limit;
  - server errors (400, 429) appear in an alert.
- **On success**, the result is stored **together with the exact code that was reviewed**. If you keep editing, findings still point at the right lines of what was reviewed.
- **`Result`**:
  - severity counts, tokens and cost;
  - the snippet rendered through the same **`DiffView`** as pull requests (an all-added patch built in the browser), with each finding's **`FindingCard`** under its line.
  - Playground findings get a temporary id and no Dismiss button: nothing is stored, so there's nothing to dismiss.
  - Usage and the dashboard queries are invalidated, so their numbers update.

### 4.7 Wiring

- `/dashboard` and `/playground` join `BUILT_PAGES` in `app/routes.tsx`. Every sidebar item up to Level 8 is now a real page.
- `lib/api/schema.d.ts` is regenerated.

### 4.8 Frontend tests

- **Mocks:**
  - `test/dashboardMock.ts`: a new-account dashboard by default, with `setDashboard()` to change it;
  - `test/playgroundMock.ts`: a tiny rules and AI imitation, plus `limitPlayground()` for the 429;
  - the usage mock gained the Playground fields.
- **`dashboard.test.tsx`** (4):
  - a new account sees the checklist with links and the empty states;
  - a set-up account sees no checklist, the totals, chart values (High 3, Low 6), the recent review's link and the Playground usage;
  - the Playground: example → Review → the SQL finding **in the row under line 3**, the debug finding under line 2, tokens and "free (mock AI)", no Dismiss buttons;
  - Review is disabled without code, the character counter works, and a 429 message appears.

**Totals:** 84 frontend tests; lint, formatting and type-check clean.

---

## 5. Verified in the browser

Signed in with **Try the demo**:
- **The dashboard** showed:
  - totals 1 / 1 / 1 / 2;
  - open findings **2 high · 4 medium · 3 low**, from the latest review only, even though the demo PR has two reviews;
  - both recent reviews;
  - usage "2 of 20" reviews, "0 of 30" Playground runs, $0.
- **The Playground:** Load an example → Review → *Debug output left in* under line 2, *SQL built from strings* (security rule) under line 3, *Exception swallowed* under line 6; 832 tokens, free (mock AI).

**Noticed, not changed:** when the backend can't be reached at the moment the page loads (for example during a restart), the app shows the sign-in page. That's a Level 1 decision ("backend unreachable → treat as signed out"). The session cookie is untouched, and a reload once the backend is back restores the session. A retry before giving up is listed for Level 11 (timeouts and retries).

---

## 6. Explain it in an interview

**How did you avoid duplicating the review logic for the Playground?**
I extracted a `ReviewEngine` with no database or GitHub dependencies: files in, findings and cost out. Pull request reviews and the Playground are thin callers. The Playground turns a snippet into a diff that adds every line, so line numbers, rules, prompt, validation and even the frontend diff view are reused unchanged.

**What did "hardening" the paste-code endpoint involve?**
The old endpoint took a raw string and forwarded it to the AI with no limits. The new one:
- takes a typed, validated request (known language, 1–20,000 characters);
- has a per-minute burst limit (Redis INCR with an expiry), a daily run limit, and a daily dollar budget shared with reviews;
- caps each run at two AI requests;
- treats the code as untrusted in the prompt and validates the model's output;
- stores usage numbers but never the code.

**How does your rate limiter work, and what are its trade-offs?**
A fixed window per user: INCR a Redis key and set a 60-second expiry on the first increment. Over the limit returns 429 with Retry-After from the key's TTL. INCR is atomic, so concurrent requests count correctly. The trade-off is boundary bursts: up to twice the limit across a window edge. A sliding log or a token bucket would fix that at more cost, and the daily limit bounds the damage anyway.

**Why does the dashboard only count the latest review per pull request?**
Re-reviewing a PR repeats the findings that weren't fixed. Summing every review would count the same problem twice. A correlated subquery keeps, for each pull request, only the review whose `createdAt` is the maximum among its successful reviews. The integration test reviews one PR twice and asserts the totals equal one review's findings.

**How do you keep AI spending in one place?**
One `AIUsageService` adds up reviews and Playground runs for today and the month. Every AI entry point calls `enforceDailyBudget` before spending, and every screen reads `usage()`. A new AI feature plugs into the same budget instead of getting its own.

**Why no chart library?**
Four horizontal bars are a few lines of CSS. A dependency would add bundle size and theming work for no gain. The numbers are printed next to the bars and the structure is a `<dl>`, so it's readable without seeing the bars.

---

## 7. Files in this level

**Backend, new:**
- `V9__playground_runs.sql`
- `entity/{PlaygroundRun, PlaygroundLanguage}`
- `repository/PlaygroundRunRepository`
- `dto/playground/{PlaygroundRequest, PlaygroundFinding, PlaygroundResponse}`, `dto/dashboard/DashboardResponse`
- `service/{AIUsageService, PlaygroundService, DashboardService}`
- `service/impl/{ReviewEngine, AIUsageServiceImpl, PlaygroundServiceImpl, DashboardServiceImpl}`
- `controller/{PlaygroundController, DashboardController}`
- tests: `PlaygroundIntegrationTest`, `DashboardIntegrationTest`, `PlaygroundServiceImplTest`

**Backend, changed:**
- `service/impl/{ReviewRunner, ReviewServiceImpl}`, `service/ReviewService`, `controller/ReviewController`
- `service/RedisService(+Impl)` (`increment`), `common/RedisKeys`
- `config/AIProperties`, `dto/review/AIUsageResponse`
- `repository/{ReviewRepository, GitHubRepositoryRepository, PullRequestRepository}`
- `application.properties`
- tests: `ReviewRunnerTest`, `ReviewServiceImplTest`, `BackendApplicationIntegrationTest`

**Backend, deleted:** `controller/AIController`, `service/AIService` + `impl/AIServiceImpl`, `dto/AIChatRequest`, `dto/AIChatResponse`

**Frontend, new:**
- `features/dashboard/{api.ts, components.tsx, pages/DashboardPage.tsx, dashboard.test.tsx}`
- `features/playground/{api.ts, pages/PlaygroundPage.tsx}`
- `features/reviews/UsageCards.tsx`
- `test/{dashboardMock, playgroundMock}.ts`

**Frontend, changed:**
- `app/routes.tsx`
- `features/reviews/{api.ts, pages/ReviewsPage.tsx}`
- `lib/api/schema.d.ts`
- `test/{reviewsMock, server, setup}.ts`

**Docs:**
- `docs/levels/LEVEL_8.md`
- `BUILD_PLAN.md`
- `IDE_GUIDE.md` (Level 8)
- `http/devpulse.http`
