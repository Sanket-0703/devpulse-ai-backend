# Level 7 — AI code review (mock AI first)

**Goal:** review a pull request's changes automatically. Findings land on the exact line of the diff, the bill stays predictable, and the whole thing works without GitHub or an AI key in a demo.

**What you can do at the end of Level 7:**
- On a **pull request page**, press **Run AI review**:
  - the review runs in the background, and the page updates by itself;
  - the panel shows counts per severity, files reviewed and skipped, AI requests, tokens and cost;
  - each finding appears **under its line in the diff** (or above the file, for whole-file findings), marked **Security rule** or **AI**, with a description and a fix.
- **Filter** by severity, **dismiss** findings (and bring them back), and switch between **earlier reviews** of the same pull request.
- **Reviews** page: every review you ran, plus today's usage against your limits (reviews per day, dollars per day) and this month's tokens.
- **Try the demo** on the sign-in page: a ready-made account with a sample repository, a pull request full of planted problems, and a finished review. No GitHub and no OpenAI needed.

> Builds on [LEVEL_6.md](LEVEL_6.md) (pull requests, changed files, the diff view).

---

## 1. The big ideas

### The review pipeline

```
POST …/pull-requests/7/reviews  ──►  row in "reviews" (QUEUED) ──► 202 Accepted
                                          │ background thread (virtual thread)
                                          ▼
 changed files (Redis cache, else GitHub)
   → ReviewPlan: skip removed / binary / generated files, parse diffs, pack into chunks
   → SecurityRules: regex checks on every ADDED line           (deterministic, free)
   → AIProvider.review(chunk) for each chunk                   (mock by default)
        until the chunks run out or the per-review budget is spent
   → Findings: validate what the model said, merge with rule findings, sort
   → save findings, tokens, cost, status SUCCEEDED (or FAILED with a reason)

the browser polls GET /api/reviews/{id} every 1.5 s until it's done
```

### Why chunks

A model reads a limited amount of text per request (its context window), and you pay per token. So a diff is split into chunks of about **12,000 characters** (≈ 3,000 tokens), one request each, **20 at most** per review.
- Small files share a chunk.
- A big file is split at **hunk boundaries**, so no hunk is cut in half.
- A single hunk bigger than a chunk is cut, and the review is marked **truncated**.

Every line the model sees starts with its **line number in the new file**:

```
File: src/main/java/com/shop/checkout/OrderRepository.java
@@ -21,8 +21,12 @@ public class OrderRepository {
    21       public Order create(CheckoutRequest request) {
           -        return jdbc.query("SELECT * FROM orders WHERE email = ?", mapper, email);
    26 +        return jdbc.query("SELECT * FROM orders WHERE email = '" + email + "'", mapper);
```

That's how the model can say "line 26", and how the finding ends up on the right row of the diff view.

### Never trust the model's answer

A model can invent files, cite lines that aren't in the diff, or answer `"severity": "urgent"`. `Findings.fromAI` checks every field before anything is saved:

| The model says | DevPulse does |
|---|---|
| a file that wasn't in this chunk | drops the finding |
| a line that isn't in the diff | keeps it as a whole-file finding (`line = null`) |
| an unknown severity or category | MEDIUM / CODE_QUALITY |
| `b/src/App.java` or `src/App.java (part 2 of 3)` | `src/App.java` |
| no file at all, but the chunk had one file | that file |
| an over-long title or description | cut to fit the database |

The prompt also treats the diff as **untrusted data**. A comment saying "ignore your instructions" is something to review, not an instruction (prompt injection).

### Rules + AI

`SecurityRules` holds nine regex rules for well-known problems:
- **CRITICAL:** committed private keys, AWS access keys;
- **HIGH:** hard-coded credentials, SQL built from strings, shell command execution, `eval`, disabled TLS checks;
- **MEDIUM:** raw HTML injection, MD5/SHA-1.

They run on **added** lines only, cost nothing, and give the same answer every time. The AI covers what a regular expression can't (logic bugs, missing checks). If the AI flags the same line a rule did, the rule's finding wins, because its wording is vetted. Repeated AI findings are dropped too.

### Mock AI first

`ai.provider=mock` is the default. `MockAIProvider` is deterministic and offline:
- it parses the same chunk format and reports a few real smells on added lines: empty `catch`, `System.out` / `console.log`, `Thread.sleep`, TODO/FIXME, lines over 160 characters;
- it **estimates tokens** (about 4 characters per token), so usage tracking works;
- its cost is **$0**.

The whole flow is built and tested without spending anything. Switching to OpenAI is `ai.provider=openai` plus `OPENAI_API_KEY`, and that's for you to decide.

### Cost control

| Limit | Default | Where |
|---|---|---|
| Reviews per user per UTC day | 20 | checked before starting → **429** with `Retry-After` until midnight |
| Estimated dollars per user per UTC day | $1.00 | the same check |
| Dollars per review | $0.25 | the job stops sending chunks and marks the review truncated |
| Chunks per review | 20 | `ReviewPlan` |

Costs are `BigDecimal` (never `double`: this is money): tokens × price per million, from `ai.pricing.*` (gpt-4o-mini prices by default).

### One running review per pull request, enforced by the database

```sql
CREATE UNIQUE INDEX uk_reviews_one_active_per_pull_request
    ON reviews (pull_request_id) WHERE status IN ('QUEUED', 'RUNNING');
```

A **partial unique index**: it only covers active rows. Two clicks at the same instant both pass any "is one running?" check in Java, but only one INSERT can succeed. The other gets a constraint violation, which the service turns into **409**. Finished reviews aren't covered by the index, so the history keeps growing. On startup, reviews left QUEUED or RUNNING by a crash are marked FAILED, or they would block that pull request forever.

### Demo mode

`app.demo.enabled=true` (local default, `DEMO_ENABLED` to change it) seeds on startup:

```
Demo User ── GitHub "demo-dev" (negative ids: never real)
  └── devpulse-demo/shop-api (AI review on)
        └── #12 "Add checkout endpoint"
              files: demo/shop-api-pr-12-files.json → written into the Redis files cache
              └── a review, run through the real pipeline
```

- The pull request's files go into **the same Redis key** real files are cached under. So the pages and the review job work unchanged, with no special "if demo" branches.
- The only demo checks are the two syncs: they refuse with "the demo account isn't connected to GitHub" instead of calling GitHub with a fake installation.
- **Try the demo** (`POST /api/auth/demo`) signs in without a password, the way GitHub sign-in does. That's exactly why demo mode must never be on for a server with real users.

---

## 2. Database: `V8__reviews.sql`

**`reviews`**: one run over one commit.
- **What was reviewed:** `pull_request_id`, plus `user_id` (who asked; limits are per user) and `head_sha`.
- **Status:** `status` (`QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`), `provider`, `model`.
- **Coverage:** `files_reviewed`, `files_skipped`, `chunks`, `truncated`.
- **Cost:** `prompt_tokens`, `completion_tokens`, `estimated_cost_usd NUMERIC(12,6)`.
- **Open findings per severity** (`critical_count` … `low_count`), so lists don't load every finding.
- **Outcome:** `error`, `started_at`, `finished_at`.
- **Indexes:**
  - `(pull_request_id, created_at DESC)` for a PR's history;
  - `(user_id, created_at DESC)` for the Reviews page and today's usage;
  - the partial unique index above.

**`review_findings`**: one problem.
- `source` (`AI` or `RULE`), `rule_id`, `severity`, `category`;
- `title`, `description`, `recommendation`;
- `file_path`, `line` (null = whole file), `dismissed`.

Both foreign keys use `ON DELETE CASCADE`, and every enum column has a `CHECK` constraint.

---

## 3. Backend, class by class

### 3.1 Entities

- **`Review`**:
  - the fields above, with `@OneToMany findings` (cascade, orphan removal) and `addFinding`;
  - **`recount()`** recomputes the four open counts from the non-dismissed findings, after a review and after every dismiss;
  - **`isActive()`**.
- **`ReviewFinding`**: the finding columns.
- **Enums:**
  - `ReviewStatus`;
  - `Severity`, whose declaration order **is** the sort order, so `Comparator.comparing(getSeverity)` puts CRITICAL first;
  - `FindingCategory`, `FindingSource`.
- **`GitHubAccount.isDemo()`**: negative GitHub ids only come from the demo seeder.

### 3.2 The pure review logic: package `review` (no Spring, easy to unit test)

**`DiffFile`** is the backend twin of the frontend's `parsePatch`.
- `parse(path, status, patch)` produces typed lines (`HUNK`, `CONTEXT`, `ADD`, `DEL`, `NOTE`) with old/new line numbers.
- `addedLines()` is what the rules scan.
- `commentableLines()` holds the new-file numbers that appear in the diff, i.e. the lines a finding may point at.
- `hunks()` is used for splitting big files.

**`ReviewPlan.of(files, maxChunkChars, maxChunks)`**
- **`isReviewable`** skips removed files, files without a patch (binary or too large), lock files, minified files, source maps, snapshots, SVGs, and `node_modules/`, `vendor/`, `dist/`, `build/`, `target/`, `.next/`.
- **`render(lines)`** produces the numbered format shown in section 1. Removed lines get `-` and no number; "no newline" notes are dropped.
- **`Packer`** packs greedily: append to the current chunk, start a new one when the next piece doesn't fit, split big files per hunk ("part 2 of 3"), and set `truncated` when out of chunks or when a hunk is cut.
- `reviewedPaths()` lists the files that made it into a chunk; `filesByPath()` is used by validation.

**`SecurityRules`**
- `Rule(id, severity, title, description, recommendation, pattern)`.
- `scan(files)` returns a `Match(rule, path, line)` for each rule matching an added line.
- The hard-coded-credential pattern **ignores placeholders** (`${…}`, `<…>`, `***`) so configuration templates aren't flagged.
- The private-key pattern is assembled from parts (`"PRIVATE" + " KEY"`), so the repository's own secret scanner doesn't flag this file.

**`Findings`**
- **`fromAI(aiFinding, chunk, files)`** applies the validation table from section 1.
- **`fromRule(match)`** turns a rule match into a finding.
- **`merge(rules, ai)`** keeps all rule findings, drops AI findings on the same file and line as a rule, and drops AI repeats (same file, line and title, ignoring case). Then it sorts.
- **`ORDER`** sorts by severity, then file, then line (whole-file first).

**`ReviewCost.estimate(provider, pricing, promptTokens, completionTokens)`**: `0` for the mock; otherwise `(in × inPrice + out × outPrice) / 1,000,000`, rounded to 6 decimals.

### 3.3 The AI layer

- **`AIProvider`** (interface):
  - `name()` ("mock" or "openai") and `model()` are stored on each review;
  - `review(input)` returns **`AIReviewResult(findings, promptTokens, completionTokens)`**.
- **`AIReviewFinding`** gains `file` and `line`.
- **`MockAIProvider`**:
  - recognises the chunk format (`File:` headers, `NN + code` lines), or treats plain input as a snippet with its own line numbers;
  - applies its smell patterns;
  - estimates tokens from the input length plus the system prompt's size.
- **`OpenAIProvider`** calls Spring AI's `ChatClient` with the model and temperature options, the system prompt and the chunk.
  - **`.responseEntity(AIReviewResponse.class)`** returns both the parsed JSON *and* the raw `ChatResponse`, whose metadata carries the real token `Usage`.
  - Any failure becomes `AIServiceException`.
- **`AIProviderConfig`** returns the mock unless `ai.provider=openai`, so nothing costs money by accident. It reads `prompts/code-review.st` once as plain text.
- **`prompts/code-review.st`** (rewritten):
  - explains the numbered diff format and asks for added lines only;
  - at most 10 findings per input, no formatter-style nitpicks;
  - an exact JSON shape with `file` and `line`;
  - the prompt-injection warning.
- **`AIProperties`**: `provider`, `model`, `temperature`, plus nested `review`, `limits` and `pricing` records. `@DefaultValue` on each field means a missing property never breaks startup.
- **`AIServiceImpl`** keeps `POST /api/ai/review` (used by the Playground in Level 8) working on the new interface.

### 3.4 `service/PullRequestFilesService` + impl (extracted)

"Get a pull request's files, from the Redis cache or GitHub" moved out of `PullRequestServiceImpl` into its own service, because the review job needs it without a user id. `cacheKey(githubRepositoryId, number, headSha)` is public so the demo seeder can fill the same key. `PullRequestServiceImpl.files` is now: ownership check, then delegate.

### 3.5 `service/impl/ReviewRunner`: the job

**`run(reviewId)`** (background thread):
1. A short transaction: `findForRun` (review + pull request + repository + account in one query), mark RUNNING, set `startedAt`.
2. `filesService.get(pullRequest)`, outside any transaction.
3. **`review(files)`**:
   - builds the plan, runs the rules;
   - loops over chunks while the cost is below `maxReviewCostUsd`;
   - adds up tokens, recomputes the cost after every call;
   - validates each AI finding against its chunk;
   - merges everything.
4. A short transaction: write the counts, tokens, cost and findings, `recount()`, SUCCEEDED, `finishedAt`.
5. **Failures** become FAILED with a message written for the user (`AIServiceException` → "The AI provider couldn't review…"; `GitHubIntegrationException` → "Couldn't load the pull request's changes from GitHub…"; anything else → "Something went wrong…"). The details go to the log.

**`failInterruptedReviews()`** (on `ApplicationReadyEvent`): one bulk `UPDATE` marks reviews still QUEUED or RUNNING as FAILED ("interrupted by a server restart").

### 3.6 `service/ReviewService` + impl

- **`start(userId, repositoryId, number)`**: one transaction does the following:
  - `findOwned` the pull request (404 for a stranger);
  - **`enforceDailyLimits`**: count and sum of today's reviews (UTC), else **429** with `Retry-After` = seconds until midnight UTC;
  - insert the review with `saveAndFlush`, so the partial unique index is checked *now*, inside the try.

  A `DataIntegrityViolationException` becomes **409**. Only **after commit** is the job handed to the executor, so the background thread can see the row.
- **`history`**: the pull request's last 20 reviews.
- **`list`**: the user's reviews, paged, newest first.
- **`get`**: review + findings (sorted).
- **`setDismissed`**: finds the finding *inside that review* (so another review's finding id is a 404), flips it, `recount()`.
- **`usage`**: today's and this month's numbers, plus the limits and the current provider and model.

Reviews are owned through `user_id`: `findByIdAndUserId` makes someone else's review a 404.

### 3.7 Repository and DTOs

**`ReviewRepository`**:
- `findForRun` (join fetch);
- `findByIdAndUserId`, `findTop20ByPullRequestId…` and `findByUserId(Pageable)`, each with an `@EntityGraph` for pull request + repository, which the DTO needs;
- `countByUserIdAndCreatedAtGreaterThanEqual`;
- `sumCostSince` and `sumTokensSince`, JPQL `coalesce(sum(...), 0)`;
- **`failAll`**, a `@Modifying` bulk update.

**DTOs** (`dto/review/`):
- **`ReviewResponse`**: everything except the findings, plus the repository's name and the PR's number and title, so lists need nothing else;
- `FindingCounts`;
- `ReviewFindingResponse`;
- `ReviewDetailResponse(review, findings)`;
- `DismissFindingRequest(@NotNull dismissed)`;
- `AIUsageResponse`.

Demo sign-in has its own: `DemoStatusResponse(enabled)`.

### 3.8 Controllers

| Endpoint | What |
|---|---|
| `POST /api/github/repositories/{id}/pull-requests/{n}/reviews` | start → **202** |
| `GET  /api/github/repositories/{id}/pull-requests/{n}/reviews` | the PR's history |
| `GET  /api/reviews?page&size` | your reviews |
| `GET  /api/reviews/usage` | usage and limits |
| `GET  /api/reviews/{id}` | one review with findings |
| `PATCH /api/reviews/{id}/findings/{findingId}` | `{dismissed: true/false}` |
| `GET  /api/auth/demo` | is demo mode on? (public) |
| `POST /api/auth/demo` | sign in to the demo account (public; 404 when off) |

The demo sign-in reuses the normal session code. **`AuthService.startSession(userId)`** issues an access token and a refresh token, and the controller sets the cookie exactly as for `/login`.

### 3.9 Demo: `DemoProperties`, `DemoService` + impl

- **`seed()`** runs on `ApplicationReadyEvent` and is safe to run on every startup. It finds or creates the user (no password), the account (gives it the placeholder installation id), the repository and PR #12 (whose `headSha` comes from the JSON file).
- It then always re-writes the files JSON into the Redis cache (30-day TTL), and starts a review through `ReviewService.start` if the PR has none.
- **`demoUserId()`** throws 404 when demo mode is off.
- **`resources/demo/shop-api-pr-12-files.json`**: six files with planted problems:
  - `CheckoutController.java`: a hard-coded key, `System.out`, a TODO, `Thread.sleep`, an empty catch;
  - `OrderRepository.java`: SQL concatenation;
  - `Passwords.java`: MD5;
  - `OrderSummary.tsx`: `dangerouslySetInnerHTML` and `console.log`;
  - a lock file and an image, both skipped.

  The planted "secret" is deliberately short (`demo1234`), so CI's gitleaks doesn't flag the demo itself.

### 3.10 Backend tests

**Unit tests:**
- **`ReviewPlanTest`** (4): line numbering; what's skipped; the exact chunk text; splitting at hunks, running out of chunks, cutting a huge hunk.
- **`SecurityRulesTest`** (23):
  - a parameterized positive example for every rule, and safe code that must not match (env variables, `${…}` placeholders, `?` parameters, SHA-256, `textContent`);
  - keys are CRITICAL; only added lines are scanned. The secret-looking samples are built at runtime.
- **`FindingsTest`** (4): valid AI findings; every kind of model mistake; rule-over-AI merge, dedup and sort; cost math.
- **`MockAIProviderTest`** (2): diff and snippet modes, token estimates.
- **`ReviewRunnerTest`** (3): AI failure, GitHub failure, and the budget stopping after 2 of 3 chunks ($0.30 > $0.25 → truncated).
- **`ReviewServiceImplTest`** (3): daily count limit (429, `Retry-After` 2 hours before midnight), daily cost limit, and the unique-index violation → 409.
- **`OpenAIProviderTest`** (2): with a deep-stubbed `ChatClient`, usage comes from response metadata; empty answers and failures are handled. No real API call.
- **`PullRequestFilesServiceImplTest`**: the files-cache tests moved here.

**Integration tests:**
- **`ReviewIntegrationTest`** (5): the full flow over WireMock GitHub:
  - a RULE finding on line 11 and an AI finding on line 12, the lock file skipped, cost 0;
  - history, list and usage;
  - dismiss and restore update the counts;
  - ownership 404s;
  - a GitHub failure → FAILED with a reason;
  - the partial unique index really rejects a second active review, and the API answers 409.
- **`DemoIntegrationTest`** (1): demo sign-in sets the cookie; the repository is there; files come from the cache; both syncs are refused; the startup review finishes with the expected rule and AI findings.

The fake-GitHub pull request helpers (`signedInWithOneRepo`, `stubPulls`, `stubFiles`, `pr`…) moved into `GitHubAppIntegrationTest`, so every GitHub test shares them.

**Totals:** 179 backend tests, all green; instruction coverage **92.8%**.

---

## 4. Frontend, file by file

### 4.1 `features/reviews/api.ts`

- **Types:** `Review`, `ReviewDetail`, `Finding`, `Severity`, `AIUsage` and `ReviewPage`, all generated.
- **`reviewKeys`:** `detail`, `forPullRequest`, `list`, `usage`.
- **Helpers:**
  - `isActive(review)`;
  - **`formatCost`**: "free (mock AI)" or `$0.0027`.
- **One function per endpoint:** `startReview`, `listPullRequestReviews`, `getReview`, `setFindingDismissed`, `listReviews` and `getUsage`.

### 4.2 `features/reviews/hooks.ts`

- **`usePullRequestReviews`** polls every 1.5 s while the newest review is active.
- **`useReview(id)`** polls until the review finishes. When it has finished, it invalidates the PR's history and the usage figures.
- **`useStartReview`** puts the new review at the top of the cached history and refreshes usage. 409 and 429 become error toasts with the server's message.
- **`useDismissFinding`** caches the server's updated review, then refreshes the lists, whose counts changed.
- **`useReviews(page)`** (with `keepPreviousData`) and **`useUsage`**.

### 4.3 `features/reviews/useReviewState.ts`

One hook holding everything the PR page needs:
- the history, and **which review is shown** (the newest, unless the user picks one; starting a new review jumps back to "newest");
- the severity filter, "show dismissed", the filtered `findings`;
- `run`, `toggleDismissed`, and their pending flags.

The panel and the diff read from the same state, so filtering in the panel also filters the diff.

### 4.4 `features/reviews/components.tsx`

- **`SeverityBadge`**: red critical, orange high, amber medium, blue low; works in dark mode.
- **`ReviewStatusBadge`**: Queued/Reviewing with a spinner, Done, Failed.
- **`FindingCountBadges`**: "2 high · 3 low", or "No open findings".
- **`FindingCard`**: an `<article>` named "High: SQL built from strings" for screen readers.
  - It shows the severity, *Security rule* or *AI*, category, title, description and fix, with **Dismiss/Restore**.
  - It has the anchor `id="finding-{id}"`.
  - A dismissed card is faded and its title struck through.

### 4.5 `features/reviews/ReviewPanel.tsx`

The **AI review** card on the PR page:
- **Run AI review** / **Review again** (disabled while one runs);
- the status badge, and a **review picker** when there's more than one: "Latest · 2 minutes ago · 0123456";
- the summary line: files reviewed and skipped, AI requests, tokens, cost, commit, time;
- the error alert for a failed review, the "part wasn't reviewed" alert for a truncated one;
- the **severity filter** and **Show dismissed (n)**;
- a **compact index** of the findings, each a link to its card on the diff (`#finding-{id}`).

### 4.6 The pull request page and the diff (changed)

- **`PullRequestDetailPage`** creates `useReviewState` and places `<ReviewPanel>` between the description and the files.
- **`FilesChanged` and `FileDiff`** get the review state. Each file:
  - shows "2 findings" in its header;
  - **opens automatically** when it has findings, even if it's big;
  - shows whole-file findings above the diff;
  - passes line findings to `DiffView`.
- **`DiffView`** gains `annotations: Map<newLine, ReactNode>`. After an added or context row whose new-file number has an annotation, it renders one extra full-width row with it. `DiffView` doesn't know about reviews; it just shows things under lines (inline comments in Level 9 will use the same prop).

### 4.7 `features/reviews/pages/ReviewsPage.tsx`

- **Usage cards:** "Reviews today: 2 of 20", "Cost today: $0.0000 of $1.00", this month's reviews and tokens, and the model ("Mock AI (free)").
- **The list:** PR title (a link to the PR page), `repo #number`, time, cost, and the counts (or the status while running). Paged.
- **Wiring:** `/reviews` moved into `BUILT_PAGES`, so the sidebar's Reviews link opens it.

### 4.8 Demo sign-in

- **`features/auth/api.ts`:** `demoEnabled()` and `demoLogin()`; the latter stores the access token like `login()` does.
- **`authContext` / `AuthProvider`:** `loginDemo()`.
- **`features/auth/DemoSignIn.tsx`:** **Try the demo (no GitHub needed)** under the sign-in form, shown only if `GET /api/auth/demo` says enabled. After sign-in, `<GuestOnly>` redirects into the app, as for a normal login.

### 4.9 Frontend tests

**`test/reviewsMock.ts`**: the review endpoints in memory. A started review answers RUNNING once, then finishes with `setNextReviewResult(...)`, mimicking the background job. Helpers: `finding()` and `addFinishedReview()`.

**`test/authMock.ts`**: `GET/POST /api/auth/demo` with `enableDemoMode()`.

**`reviews.test.tsx`** (8):
- run a review → counts, "free (mock AI)", the rule finding **in the row right under line 2**, the whole-file finding, index links, "2 findings" on the file;
- dismiss → gone from the diff and the counts; Show dismissed → back with Restore; the Low filter;
- a failed review shows its reason;
- switching between two reviews;
- the Reviews page with usage, and its empty state;
- **Try the demo** signs in, and is hidden when demo mode is off.

**`AppLayout.test.tsx`**: the sidebar test now visits a page that's still a placeholder (Code search).

**Totals:** 80 frontend tests; lint, formatting and type-check clean.

---

## 5. Verified in the browser

Signed in with **Try the demo**, then on `devpulse-demo/shop-api` #12:
- the panel shows "2 high · 4 medium · 3 low", 4 files reviewed and 2 skipped, 1 AI request, about 1,700 tokens, free (mock AI);
- the cards sit under their lines: *Hard-coded credential* under line 12, *Debug output left in* under 24, *Unfinished work* under 25;
- **Review again** created a second review, and the Reviews page shows both, with "2 of 20" reviews today and $0 cost.

One bug was found and fixed while checking this: the demo repository was hidden behind "Install the DevPulse app" (the page needs an installation). The demo account now has a placeholder installation, and both syncs refuse it with a clear message.

---

## 6. Explain it in an interview

**Walk me through how a review runs.**
The POST inserts a QUEUED row and returns 202; after the commit, a virtual-thread executor runs the job. The job loads the pull request's files (from Redis, else GitHub), skips generated and binary files, parses the diffs and packs them into chunks of about 3,000 tokens. Regex security rules run on every added line. Each chunk goes to the AI provider, until the chunks run out or the per-review budget is spent. The model's findings are validated against what it was shown, merged with the rule findings, and saved with token counts and estimated cost. The browser polls until it's done.

**How do you make sure a finding lands on the right line?**
Every line sent to the model carries its line number in the new file, taken from the hunk headers. The model must cite those numbers, and I check each cited line against the set of lines that appear in the diff. A line that doesn't exist becomes a whole-file finding instead of pointing at the wrong code. The frontend renders findings under the row with that new-file number.

**How do you stop LLM output from corrupting your data?**
I treat it as untrusted input. The JSON is parsed into a typed record, then every field is validated: the file must be one the chunk contained, the line must exist in the diff, and severity and category must be known enum values (else a safe default). Text is length-limited. The prompt also says the diff is data, not instructions, against prompt injection hidden in code comments.

**How do you control AI cost?**
Four limits: chunks per review, dollars per review (the loop stops and marks the review truncated), and reviews and dollars per user per day (429 with Retry-After until midnight UTC). Costs come from real token usage returned by the API (Spring AI's response metadata), times per-million prices from config, in BigDecimal. And the default provider is a mock that costs nothing.

**Why both regex rules and AI?**
They're good at different things. Rules are deterministic, free and instant, so a hard-coded key or SQL concatenation is caught every time. The model catches what patterns can't: logic errors, missing validation. When both flag the same line, the rule's vetted wording wins, so the user doesn't see duplicates.

**How do you prevent two reviews of the same PR running at once?**
A partial unique index on `reviews(pull_request_id) WHERE status IN ('QUEUED','RUNNING')`. A check in Java has a race window; the database doesn't. The service flushes the insert inside its try block, turns the constraint violation into 409, and only starts the job after commit. Reviews orphaned by a crash are failed on startup, so the index can't block a PR forever.

**What is the demo mode, and what's the risk?**
A seeded account with a sample PR whose files are written into the same Redis cache real files use, so every page and the review job run unchanged. The risk is that it signs in without a password. It's local-only by configuration, and the docs say never to enable it with real users.

**Why a mock AI provider?**
To build and test the whole pipeline (chunking, validation, storage, UI, limits) deterministically and for free. The interface is the same as the real provider's, and swapping is a config change. A real model's output differs run to run, which makes tests flaky; the mock's doesn't.

---

## 7. Files in this level

**Backend, new:**
- `V8__reviews.sql`
- `entity/{Review, ReviewFinding, ReviewStatus, Severity, FindingCategory, FindingSource}`
- `review/{DiffFile, ReviewPlan, SecurityRules, Findings, ReviewCost}`
- `dto/AIReviewResult`, `dto/DemoStatusResponse`, `dto/review/*`
- `repository/ReviewRepository`
- `service/{ReviewService, PullRequestFilesService, DemoService}`
- `service/impl/{ReviewServiceImpl, ReviewRunner, PullRequestFilesServiceImpl, DemoServiceImpl}`
- `controller/ReviewController`
- `config/DemoProperties`
- `resources/demo/shop-api-pr-12-files.json`
- tests: `ReviewPlanTest`, `SecurityRulesTest`, `FindingsTest`, `MockAIProviderTest`, `ReviewRunnerTest`, `ReviewServiceImplTest`, `OpenAIProviderTest`, `PullRequestFilesServiceImplTest`, `ReviewIntegrationTest`, `DemoIntegrationTest`

**Backend, changed:**
- AI: `service/AIProvider`, `service/impl/{MockAIProvider, OpenAIProvider, AIServiceImpl}`, `config/{AIProperties, AIProviderConfig}`, `dto/AIReviewFinding`, `prompts/code-review.st`
- pull requests: `service/impl/PullRequestServiceImpl`, `controller/PullRequestController` (review endpoints)
- auth: `controller/AuthController`, `service/AuthService(+Impl)` (`startSession`), `config/SecurityConfig`
- syncs: `service/impl/{RepositorySyncServiceImpl, PullRequestSyncServiceImpl}` (refuse the demo)
- `entity/GitHubAccount` (`isDemo`), `BackendApplication`, `application.properties`
- tests: `GitHubAppIntegrationTest`, `PullRequestIntegrationTest`, `PullRequestServiceImplTest`, `BackendApplicationIntegrationTest`

**Frontend, new:**
- `features/reviews/{api.ts, hooks.ts, useReviewState.ts, components.tsx, ReviewPanel.tsx, pages/ReviewsPage.tsx, reviews.test.tsx}`
- `features/auth/DemoSignIn.tsx`
- `test/reviewsMock.ts`

**Frontend, changed:**
- `features/pull-requests/{DiffView.tsx, pages/PullRequestDetailPage.tsx}`
- `features/auth/{api.ts, authContext.ts, AuthProvider.tsx, pages/LoginPage.tsx}`
- `app/routes.tsx`
- `lib/api/schema.d.ts`
- `test/{authMock, server, setup}.ts`
- `components/layout/AppLayout.test.tsx`

**Docs:**
- `docs/levels/LEVEL_7.md`
- `BUILD_PLAN.md`
- `IDE_GUIDE.md` (Level 7)
- `http/devpulse.http`
