# Level 9 — Webhooks & GitHub comments

**Goal:** close the loop with GitHub. When someone opens a pull request or pushes to one, GitHub tells DevPulse, DevPulse reviews it, and the findings appear **on the pull request itself** as review comments, on the right lines.

**What you can do at the end of Level 9:**
- **Automatic reviews:** a push to a pull request (or opening one) in a repository with AI review on starts a review, marked **Automatic**, with no button pressed.
- **Publish to GitHub** on any finished review, after a confirmation. DevPulse posts **one pull request review** with:
  - an inline comment on each finding's line;
  - a summary with the counts and anything that isn't on a line.

  A link takes you to it on GitHub.
- **Settings → Reviews:**
  - review automatically (on by default);
  - publish automatic reviews without asking (off by default);
  - what to publish: critical only, high and above, **medium and above** (default), or everything;
  - the latest **webhook deliveries**, with what DevPulse did with each one.
- **[WEBHOOKS_LOCAL.md](../WEBHOOKS_LOCAL.md):** how to let GitHub reach your Mac through a smee.io tunnel.

> Builds on [LEVEL_7.md](LEVEL_7.md) (reviews), [LEVEL_6.md](LEVEL_6.md) (pull requests) and [LEVEL_5.md](LEVEL_5.md) (installation tokens).

---

## 1. The big ideas

### Webhooks: GitHub calls us

Until now, DevPulse *asked* GitHub (sync buttons). A webhook turns that around: when something happens, GitHub sends an HTTP POST to a URL we chose, with a JSON description of the event.

```
POST /api/github/webhooks
X-GitHub-Event:      pull_request
X-GitHub-Delivery:   7f3c9e2a-…            (unique per delivery)
X-Hub-Signature-256: sha256=9a1b…          (HMAC of the body with our shared secret)

{"action":"synchronize","number":7,"pull_request":{…},"repository":{"id":…},"installation":{"id":…}}
```

The endpoint is **public**: no access token, because GitHub doesn't have one. Anyone on the internet can call it, so each step is a defence:

```
1. secret configured?      no  → 503   (never accept what we can't verify)
2. signature valid?        no  → 401   (not from GitHub, or changed on the way)
3. delivery id seen before? yes → 200 "duplicate"  (GitHub retries; "Redeliver")
4. ping                        → 200 "pong"
5. everything else             → 202, processed on a background thread
```

### The signature: HMAC-SHA256

GitHub and DevPulse share a secret. GitHub computes `HMAC-SHA256(secret, raw body)` and sends it in `X-Hub-Signature-256`. DevPulse computes the same over **the exact bytes it received** and compares. Without the secret nobody can produce a matching value, and changing one byte of the body changes it completely.

Two details matter:
- **Raw bytes, not parsed JSON.** The controller takes the body as `byte[]`. Re-serializing parsed JSON could change spacing or key order, and the signature would no longer match.
- **Constant-time comparison** (`MessageDigest.isEqual`). A normal `equals` stops at the first different character, so an attacker timing many guesses could learn the signature character by character. `isEqual` takes the same time regardless of where the difference is.

The test uses **GitHub's own documented example** (secret "It's a Secret to Everybody", body "Hello, World!") and gets the exact signature from the docs.

### Duplicate deliveries: a unique constraint

GitHub may send the same delivery twice: a retry after a timeout, or you pressing *Redeliver* while debugging. Reviewing twice would waste money and post duplicate comments.

Every delivery is inserted into `webhook_deliveries`, which has **`UNIQUE (delivery_id)`**. The second insert of the same id fails, and we answer `200 duplicate delivery, ignored`.
- Same idea as Level 7's "one active review" index: let the database enforce it, with no race window.
- It survives restarts, unlike a Redis key with a TTL.
- The table doubles as an **audit log** shown in Settings → Reviews.

### Answer fast, work later

GitHub waits at most **10 seconds** for an answer. A review takes longer, so the request only verifies, records and returns **202 Accepted**; the work happens on the background executor (virtual threads, as since Level 5). The delivery row goes from `RECEIVED` to `PROCESSED` / `IGNORED` / `FAILED`, with a sentence saying what happened.

### What each event does

| Event | Action | DevPulse does |
|---|---|---|
| `pull_request` | `opened`, `reopened`, `synchronize` (new commits), `ready_for_review` | updates the stored PR, then **starts a review** for each user who follows the repository, if: not a draft, AI review on for the repo, automatic reviews on in their settings |
| `pull_request` | `edited`, `closed`, `converted_to_draft` | updates the stored PR (title, state, branches…) |
| `installation_repositories` | added / removed | syncs the installation's accounts (the Level 5 sync) |
| `installation` | `deleted`, `suspend` | forgets the installation; its repositories and PRs are removed |
| `installation` | `unsuspend`, `new_permissions_accepted` | syncs again |
| `ping` | — | answers `pong` |
| anything else | — | recorded as *ignored* |

**Which users?** A delivery names an installation and a repository. DevPulse updates every repository row with that GitHub repository id **and** that installation, since two DevPulse users can share an organization's installation. A delivery for another installation can never touch your data.

**Every "no" is explained.** Reasons not to review (draft, review off, the user turned automatic reviews off, already running, daily limit) are written into the delivery's detail, not thrown away. That's what you read in Settings → Reviews when something "didn't happen".

### Publishing: one GitHub review with inline comments

```
POST /repositories/{id}/pulls/{number}/reviews        (installation token)
{
  "commit_id": "<the reviewed head sha>",
  "event": "COMMENT",                 ← feedback only; never approves or blocks
  "body": "### DevPulse AI review …",  ← counts + findings not on a line
  "comments": [
    {"path": "src/Dao.java", "line": 12, "side": "RIGHT", "body": "**High · Security** — SQL built from strings …"}
  ]
}
```

- **`line` + `side: RIGHT`** means "line 12 of the new version of the file". That's exactly what our findings store, since Level 6 numbered every diff line by its new-file number. This is the moment that design pays off.
- **`commit_id`** is the commit that was reviewed. If someone pushed since, GitHub still attaches the comments to the older commit (shown as "outdated").
- **One review, not 20 separate comments:** one notification for the author, and everything grouped.
- **What gets published:** open (not dismissed) findings at or above the minimum severity, as inline comments, at most **30**. Whole-file findings and anything over the cap are listed in the summary.
- **Never twice:** a Redis lock (`SET NX`) stops two simultaneous clicks, and `published_at` stops a second publish later (409 "already on GitHub").
- **Asked first:** the button opens a confirmation, because the post is public on the pull request. Automatic publishing happens only for automatic reviews, and only if the user turned it on.

---

## 2. Database: `V10__webhooks_and_publishing.sql`

- **`webhook_deliveries`**:
  - `delivery_id` (**unique**), `event`, `action`;
  - `installation_id`, `github_repository_id`;
  - `status` (`RECEIVED`/`PROCESSED`/`IGNORED`/`FAILED`), `detail`;
  - index `(installation_id, created_at DESC)` for "your latest deliveries".
- **`review_settings`**: one row per user, **keyed by `user_id`** (no separate id: a user has at most one settings row).
  - `auto_review` (default true), `auto_publish` (default false);
  - `min_publish_severity` (default MEDIUM, with a `CHECK`).

  A row is only created when the user changes something; until then the defaults apply.
- **`reviews`** gains:
  - `triggered_by` (`MANUAL`/`WEBHOOK`). Not called `trigger`, which is an SQL keyword;
  - `published_at`, `github_review_id`, `github_review_url`, `published_comments`.

---

## 3. Backend, class by class

### 3.1 Entities

- **`WebhookDelivery`** and the **`WebhookDeliveryStatus`** enum.
- **`ReviewSettings`**:
  - `@Id userId` (not generated), the defaults as field initializers, its own timestamps;
  - **`publishes(severity)`**: is this severity at or above the minimum? It compares enum order, CRITICAL first.
- **`ReviewTrigger`**: `MANUAL`, `WEBHOOK`.
- **`Review`** gains `triggeredBy`, `publishedAt`, `githubReviewId`, `githubReviewUrl`, `publishedComments`.

### 3.2 `security/WebhookSignature`

- `sign(secret, body)` → `"sha256=" + hex(HMAC-SHA256)`.
- **`isValid(secret, body, header)`**: false for a missing header or a wrong prefix; otherwise a constant-time comparison with what we'd sign.

### 3.3 `service/GitHubWebhookService` + impl

**`receive(event, deliveryId, signature, body)`** returns `Outcome` (`PONG`, `ACCEPTED`, `DUPLICATE`):
1. no secret → `WebhooksNotConfiguredException` (503);
2. bad signature → `InvalidWebhookSignatureException` (401);
3. parse with Jackson 3's `JsonMapper.readTree` (not JSON → `InvalidWebhookPayloadException`, 400);
4. `saveAndFlush` the delivery: a `DataIntegrityViolationException` on the unique id means `DUPLICATE`;
5. `ping` → mark processed, `PONG`; otherwise hand `process(...)` to the background executor, `ACCEPTED`.

**`process(rowId, event, payload)`** dispatches on the event. Each handler returns a **`Result(status, detail)`**, and the row is updated with it. An unexpected exception marks the delivery `FAILED`, with a generic message (details in the log).

**`pullRequest(payload)`**
- Ignores actions we don't use.
- Converts `payload.pull_request` into the same `GitHubPullRequestResponse` the Level 6 sync uses (`treeToValue`).
- **`upsert`** (one transaction):
  - finds the repository rows via `findByGithubIdAndGithubAccountInstallationId`;
  - creates or updates PR #number with Level 6's `PullRequestSyncServiceImpl.copy(...)`, kept static exactly so it could be reused here;
  - returns `Target(userId, repositoryId, reviewEnabled)` for each.
- For review actions, **`review(target, source)`** checks draft → repository switch → the user's `autoReview`, then `reviewService.start(…, ReviewTrigger.WEBHOOK)`. A 409 (already running) or a 429 (daily limit) becomes a sentence, not an error.

**`installation(payload)`**: `deleted`/`suspend` → `unlinkInstallation`; `unsuspend`/`new_permissions_accepted` → `syncInstallation`.

**`recentDeliveries(userId)`**: the 10 newest deliveries for the user's installation.

### 3.4 Repository sync additions (`RepositorySyncService`)

- **`syncInstallation(installationId)`** starts the Level 5 background sync for every (non-demo) account with that installation.
- **`unlinkInstallation(installationId)`** clears the installation id and applies an *empty* repository list. The Level 5 reconciliation then deletes the repositories, and their pull requests and reviews cascade.

### 3.5 `service/ReviewPublishingService` + impl

- **`publish(userId, reviewId)`**: ownership check (`findByIdAndUserId` → 404), then **`doPublish`**, then return the updated review.
- **`publishAutomatically(reviewId)`** is used by the review job. A failure is logged, not thrown: the review succeeded, and the user can still publish by hand.
- **`doPublish`**:
  - takes the Redis lock `lock:review:publish:{id}`;
  - builds a `Plan` in one transaction (installation, repository, number, request);
  - calls GitHub **outside** any transaction;
  - saves the GitHub review id, URL, count and time;
  - releases the lock in `finally`.

  On a generic GitHub error it invalidates the cached installation token (it may have been revoked).
- **`plan(review)`** refuses (409) when the review isn't finished, is already published, belongs to the demo account, or has no installation.
- **`request(review, settings)`** (static, unit-tested): filters, sorts with `Findings.ORDER`, and splits the findings into ≤ 30 inline comments plus a summary list.
- **`commentBody`**: `**High · Security** — title`, the description, `**Fix:** …`, and a small "DevPulse AI · security rule `id`" or "AI review" footer.
- **`summary`**: the reviewed commit, counts per severity (or "no findings at … severity or above ✅"), "Not on a specific line", and a note if the review was truncated.

### 3.6 Review job and service changes

- **`ReviewService.start(…, trigger)`** is new. The old 3-argument `start` is now a `default` method passing `MANUAL`, so existing callers didn't change. The trigger is stored on the review.
- **`ReviewRunner`**: when a review succeeds, the same transaction decides whether to publish (`WEBHOOK` trigger and the user's `autoPublish`). After the commit it calls `publishAutomatically`.

### 3.7 GitHub client

- **`createPullRequestReview(token, repoId, number, request)`**: `POST /repositories/{id}/pulls/{number}/reviews` with the records **`GitHubCreateReviewRequest`** (`commit_id` via `@JsonProperty`) and **`GitHubReviewResponse`** (`id`, `html_url`).
- **`call(...)`** maps **422 Unprocessable Content** to the reason `github_rejected`. The global handler turns that into a 502 with advice: "If the pull request changed since the review, review it again; otherwise check the app has *Pull requests: write*".

### 3.8 Settings

- **`ReviewSettingsService` + impl**:
  - `get` (with `webhooksConfigured`, so the UI can warn);
  - `update`, which creates the row on the first change;
  - `settingsOf`, the stored settings or unsaved defaults, used by the webhook handler, the job and the publisher.
- DTOs: `ReviewSettingsRequest` (all three fields `@NotNull`) and `ReviewSettingsResponse`.

### 3.9 Controllers, security, configuration

| Endpoint | Who calls it |
|---|---|
| `POST /api/github/webhooks` | GitHub (public; signature-checked) |
| `GET /api/github/webhooks/deliveries` | the settings page |
| `GET/PUT /api/settings/reviews` | the settings page |
| `POST /api/reviews/{id}/publish` | the review panel |

- `SecurityConfig` permits only `POST /api/github/webhooks` without a token.
- `GlobalExceptionHandler` adds: 401 bad signature, 400 bad payload, 503 not configured.
- **Configuration:**
  - `github.webhook-secret=${GITHUB_WEBHOOK_SECRET:}`; blank means webhooks are refused;
  - `.env.example` documents it, with `openssl rand -hex 32`;
  - the test profile uses a fixed test secret.

### 3.10 Backend tests

- **`WebhookSignatureTest`** (2): GitHub's documented example; missing, wrong-prefix, wrong-secret, changed-body and upper-cased signatures are all rejected.
- **`ReviewPublishingServiceImplTest`** (3):
  - inline comment vs summary, minimum severity, dismissed excluded, comment and summary text;
  - 32 line findings → 30 comments plus 2 in the summary;
  - a clean review's summary and the truncated note.
- **`GitHubWebhookServiceImplTest`** (4): no secret (null, empty, blank) → refused before touching the database; the review actions.
- **`WebhookIntegrationTest`** (10), over WireMock GitHub with real signatures:
  - invalid or missing signature → 401; ping → pong; the same delivery id again → duplicate; not JSON → 400;
  - `synchronize` → the PR is updated (new title, new head sha), an **automatic** review runs on the new commit, and the delivery says "#7 synchronize. Review started.";
  - no review for repo-off, draft or user-opted-out, each with its sentence;
  - an event for a repository DevPulse doesn't follow → ignored;
  - **publish**: the request to GitHub carries the installation token, `commit_id`, `COMMENT`, path, line and `RIGHT`, and HIGH only (LOW is below MEDIUM); the URL and count are saved; a second publish → 409; another user → 404;
  - **auto-publish** with minimum LOW → both findings posted;
  - GitHub 422 → 502 with advice, nothing recorded (it can be retried);
  - `installation` deleted → repositories removed; `installation_repositories` added → synced;
  - settings defaults, update, and validation (400).
- **Updated:** `ReviewRunnerTest` (new constructor), `GitHubAppJwtSignerTest` (the new property), `BackendApplicationIntegrationTest` (migrations 1–10).

**Totals:** 204 backend tests; `mvn verify` exits 0; instruction coverage **93.2%**.

---

## 4. Frontend, file by file

### 4.1 `features/settings/api.ts` and `reviewSettingsHooks.ts`

- **API:** `getReviewSettings`, `saveReviewSettings` and `listWebhookDeliveries`, with their generated types.
- **`useSaveReviewSettings`** saves the *whole* settings object on every change (like the repository review switch), puts the server's answer into the cache, and toasts "Review settings saved".

### 4.2 `features/settings/pages/ReviewSettingsPage.tsx`

**Review preferences:**
- two switches and a "What to publish" select, each with a sentence explaining it;
- every change is saved immediately; the controls are disabled while saving.

**GitHub webhooks:**
- a warning when the server has no secret, pointing to the guide;
- **Refresh**, and the latest deliveries: `event.action`, a status badge, time, and DevPulse's sentence;
- "No deliveries yet" when there are none.

`SettingsLayout` gets the fourth tab, **Reviews**; the route is `/settings/reviews`.

### 4.3 `features/reviews/PublishControl.tsx`

- **Not published:** **Publish to GitHub** opens an `AlertDialog` saying what will be posted ("an inline comment for each open finding of medium severity or above", using your setting), and that everyone with access will see it. *Publish* or *Cancel*.
- **Published:** "Published to GitHub 2 minutes ago · 3 comments · View on GitHub ↗".
- **`usePublishReview`** puts the updated review into the cached detail, refreshes the history, and toasts "Published to GitHub with N comments". Errors (409, 502) appear as toasts with the server's message.

### 4.4 `ReviewPanel`

- an **Automatic** badge for webhook-started reviews;
- the publish control under a finished review's summary.

### 4.5 Frontend tests

- **`test/reviewsMock.ts`** gained:
  - settings (`setReviewSettings`, `storedReviewSettings`) and deliveries (`setWebhookDeliveries`);
  - publishing (counts line findings at or above the minimum), plus `failNextPublish()`.
- **`reviewSettings.test.tsx`** (7):
  - each preference is saved as it changes, and earlier changes are kept;
  - deliveries with their status and detail;
  - the "not set up" warning and the empty state;
  - the settings tab;
  - publish: confirm dialog (mentions the minimum) → toast "Published to GitHub with 1 comment" → link to GitHub, button gone;
  - Cancel publishes nothing, and a GitHub refusal shows its message with the button still there;
  - the Automatic badge.

**Totals:** 91 frontend tests; lint, formatting and type-check clean.

---

## 5. Verified

- **Locally, against the running backend:**
  - with no `GITHUB_WEBHOOK_SECRET` in `.env`, `POST /api/github/webhooks` answers **503**: nothing is accepted until a secret exists;
  - **Settings → Reviews** shows the preferences, the "not set up" warning and the empty delivery list;
  - on the demo account, **Publish to GitHub** → the confirmation → "The demo account isn't connected to GitHub, so it can't publish."
- **The real round trip** (a push to a real PR → automatic review → comments on GitHub) needs three things you do in your GitHub App's settings: the webhook secret, a tunnel, and the *Pull request* event, as in [WEBHOOKS_LOCAL.md](../WEBHOOKS_LOCAL.md). It's part of the end-to-end pass. Everything up to GitHub's API is covered by the integration tests against a fake GitHub, including the exact request body.

---

## 6. Explain it in an interview

**How do you secure a public webhook endpoint?**
GitHub signs each delivery with HMAC-SHA256 of the raw body, using a shared secret. I verify that over the exact bytes received, with a constant-time comparison, before parsing anything. Without a configured secret the endpoint refuses everything (503) rather than trusting unverified input. The endpoint is the only public POST in the security config, and a delivery can only touch repositories of the installation it names.

**What if GitHub delivers the same event twice?**
Each delivery has a unique id. I insert it into a table with a unique constraint before doing anything; a duplicate insert fails and I answer 200 "duplicate, ignored". The database guarantees it even under concurrency and across restarts, and the table doubles as an audit log the user can read.

**Why return 202 and process in the background?**
GitHub times out after 10 seconds and a review takes longer. So the request does only the cheap, important parts (verify, deduplicate, record) and returns 202. The work runs on a virtual-thread executor, and the delivery's status and detail record what happened.

**How do your findings end up on the right line on GitHub?**
Since the diff parser in Level 6, every finding stores a line number in the *new* version of the file. GitHub's review API takes exactly that: `line` with `side: RIGHT`, plus the reviewed `commit_id`. Findings that aren't on a line, or exceed a 30-comment cap, go into the review's summary. It's posted as one review with event COMMENT, so it informs without approving or blocking.

**How do you avoid double-posting and noisy pull requests?**
A Redis `SET NX` lock covers simultaneous clicks, and a `published_at` check covers later repeats (409). The noise controls: a minimum severity (default medium), dismissed findings excluded, the 30-comment cap, one grouped review instead of many comments, and manual publishing behind a confirmation. Automatic publishing is opt-in.

**Why would an automatic review not run, and how would a user know?**
Draft PR, AI review off for the repo, automatic reviews turned off, one already running, or the daily limit. Each reason is written into the delivery's detail and shown in Settings → Reviews, so "nothing happened" always has an explanation.

---

## 7. Files in this level

**Backend, new:**
- `V10__webhooks_and_publishing.sql`
- `entity/{WebhookDelivery, WebhookDeliveryStatus, ReviewSettings, ReviewTrigger}`
- `repository/{WebhookDeliveryRepository, ReviewSettingsRepository}`
- `security/WebhookSignature`
- `service/{GitHubWebhookService, ReviewPublishingService, ReviewSettingsService}` + impls
- `controller/{GitHubWebhookController, ReviewSettingsController}`
- `dto/github/{GitHubCreateReviewRequest, GitHubReviewResponse}`, `dto/settings/*`, `dto/webhook/*`
- `exception/{InvalidWebhookSignatureException, InvalidWebhookPayloadException, WebhooksNotConfiguredException}`
- tests: `WebhookSignatureTest`, `ReviewPublishingServiceImplTest`, `GitHubWebhookServiceImplTest`, `WebhookIntegrationTest`

**Backend, changed:**
- `entity/Review`, `dto/review/ReviewResponse`
- `service/ReviewService` + impl, `service/impl/ReviewRunner`
- `service/RepositorySyncService` + impl
- `client/GitHubApiClient(+Impl)`
- `repository/{GitHubRepositoryRepository, GitHubAccountRepository}`
- `controller/ReviewController`, `config/{GitHubProperties, SecurityConfig}`
- `exception/GlobalExceptionHandler`, `common/RedisKeys`
- `application.properties`, `.env.example`
- tests: `ReviewRunnerTest`, `GitHubAppJwtSignerTest`, `BackendApplicationIntegrationTest`, `application-test.properties`

**Frontend, new:**
- `features/settings/{reviewSettingsHooks.ts, pages/ReviewSettingsPage.tsx, reviewSettings.test.tsx}`
- `features/reviews/PublishControl.tsx`

**Frontend, changed:**
- `features/settings/{api.ts, SettingsLayout.tsx}`
- `features/reviews/{api.ts, hooks.ts, ReviewPanel.tsx}`
- `app/routes.tsx`
- `lib/api/schema.d.ts`
- `test/reviewsMock.ts`
- `features/dashboard/dashboard.test.tsx`

**Docs:**
- `docs/WEBHOOKS_LOCAL.md` (new)
- `docs/GITHUB_APP_SETUP.md`
- `docs/levels/LEVEL_9.md`
- `BUILD_PLAN.md`
- `IDE_GUIDE.md` (Level 9)
- `http/devpulse.http`
