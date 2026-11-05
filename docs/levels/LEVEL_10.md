# Level 10 — Code indexing, semantic search & RAG

**Goal:** let DevPulse *understand* a repository's code. Each repository is indexed into pgvector with a free local embedding model, so you can **search code by meaning**, and AI reviews see **related code from the rest of the repository** (RAG).

**What you can do at the end of Level 10:**
- On a **repository page**, a **Code index** card:
  - **Index code** downloads the default branch, splits it into chunks and embeds them, in the background with live status;
  - then it shows files, chunks, the indexed commit, and when;
  - **Re-index** after changes: unchanged code reuses its cached embeddings.
- **Code search** (sidebar):
  - describe what you're looking for in plain words ("where are JWT tokens validated"), across all your indexed repositories or one;
  - results show the file, the lines, a % match, the code with line numbers, and a link to those lines on GitHub;
  - queries live in the URL, so they can be shared.
- **Reviews of indexed repositories** send each piece of the diff to the model together with the most related existing code; the review shows "3 related code snippets as context".
- **The demo account's** shop API is indexed too (from a bundled archive), so search works without GitHub.

The plan's check, automated as a test: indexing **DevPulse's own backend** (203 files → 657 chunks, embedded in about 8 seconds) and searching "where are JWT tokens validated" puts `JwtAuthenticationFilter` / `JwtService` in the **top 3**.

> Builds on [LEVEL_7.md](LEVEL_7.md) (the review engine) and [LEVEL_5.md](LEVEL_5.md) (installation tokens, background jobs).

---

## 1. The big ideas

### Embeddings: meaning as numbers

An **embedding model** turns a text into a list of numbers, a *vector*. Ours, all-MiniLM-L6-v2, gives **384 numbers** per text, and it's trained so that texts with similar meaning get vectors pointing in similar directions:

```
"verify the JWT signature and expiry"     → [0.12, -0.03, 0.41, …]  ┐ close
"where are JWT tokens validated"          → [0.10, -0.01, 0.39, …]  ┘
"render the invoice as a PDF"             → [-0.33, 0.27, 0.02, …]    far
```

**Cosine similarity** measures the angle between two vectors: 1 means the same direction (same meaning), 0 means unrelated. Search becomes: embed the question, then find the stored code vectors closest to it. That's how "JWT tokens validated" finds `JwtService.isTokenValid` even though the code never contains that sentence.

- **Why a local model:** it runs inside the JVM (ONNX via Spring AI's transformers module). Zero cost and no API key, and code never leaves your machine. The model is pinned to a fixed version, so stored vectors stay comparable.
- **The trade-off:** it's small and general-purpose. A larger code-specific model would rank better, at a cost.

### pgvector: vectors in Postgres

The `vector(384)` column type and the `<=>` (cosine distance) operator come from the pgvector extension, enabled since V2. Searching is plain SQL:

```sql
SELECT path, start_line, end_line, content, 1 - (embedding <=> '[0.10,-0.01,…]'::vector) AS score
FROM code_chunks … WHERE a.user_id = 7
ORDER BY embedding <=> '[0.10,-0.01,…]'::vector
LIMIT 10;
```

**The HNSW index** (`USING hnsw (embedding vector_cosine_ops)`) makes this fast. It's a layered graph where each vector links to its near neighbours; a search walks it from a coarse layer down to fine ones, instead of comparing against every row. It's *approximate* (it can occasionally miss the true closest row), which is the standard trade for speed. With a `WHERE` filter (your repositories only), pgvector filters the candidates the index returns; for very selective filters, a higher `hnsw.ef_search` keeps enough candidates.

**Why our own table**, not Spring AI's generic `vector_store`:
- **Flyway owns the schema.** The library used to create its table at startup (the old TODO M6-01); V11 drops it.
- **Real columns** (repository, path, lines, language) instead of a JSON blob, so:
  - searches are scoped with a normal join to *your* repositories;
  - a repository is re-indexed with `DELETE … WHERE repository_id = ?`;
  - deleting a repository cascades.
- **Plain SQL with JdbcTemplate:** JPA has no `vector` type, and the queries are short.

### Indexing a repository

```
default branch → its head commit (GET /repositories/{id}/branches/main)
  → the repository at that commit as ONE zip (GET /repositories/{id}/zipball/<sha>)
  → source files: code only, bounded
  → chunks of ≤ 40 lines / 1,000 characters (prefer ending at a blank line; 3 lines of overlap)
  → embeddings (Redis cache by content hash; the rest in batches of 64)
  → one transaction: delete the repository's old chunks, insert the new ones
```

- **One download, not one call per file.** GitHub's zipball gives the whole repository at a commit in one request; Java's built-in `ZipInputStream` reads it, no extra library.
- **Bounded, because it comes from the network.** Files over 200 KB are skipped, and reading stops at 3,000 files or 50 MB of text. Each entry is read through a loop that gives up past the limit, so a **zip bomb** (a small archive that expands to gigabytes) can't exhaust memory.
- **Only code.** An allowlist of extensions (Java, Kotlin, TypeScript, Python, Go, SQL…) skips:
  - data and docs (`.properties` and `.env` can hold secrets);
  - dependencies and build output (`node_modules/`, `target/`, `dist/`…);
  - generated files (`.d.ts`, `.min.js`);
  - binaries (detected by a zero byte).
- **Why chunks:** the model reads about 256 word pieces; anything longer is cut off and invisible to search. A chunk ends at a blank line in its second half when possible, so methods tend to stay together. The 3-line overlap means code on a boundary appears whole in one chunk.
- **The path is part of the embedded text** (`File: src/…/security/JwtService.java`). File names carry meaning, and searches match on them too.
- **Atomic replace:** the old chunks are deleted and the new ones inserted in **one transaction**, so a search during a re-index sees the old index or the new one, never half of each.

### The embedding cache: pay once per piece of code

The key is the **SHA-256 of the exact embedded text**: `cache:embedding:all-MiniLM-L6-v2:<hash>`.
- **Re-indexing** after a commit that changes 2 files embeds only the chunks of those 2 files; everything else comes from Redis. The test's logs show it: "3 files, 3 chunks (3 embeddings from cache)".
- **The model name is in the key**, so switching models can't mix vectors from different models.
- **Compact storage:** a vector is 384 float32 values, Base64-encoded, about **2 KB**, versus ~8 KB as JSON text.
- **One round trip:** all cache lookups go in a single `MGET`.

### RAG: reviews that see more than the diff

**Retrieval-Augmented Generation:** before asking the model, *retrieve* relevant information and add it to the prompt. For each chunk of a pull request's diff:
1. embed the chunk's text;
2. find the 3 nearest code chunks of the same repository, **skipping the files the diff changes** (the model already sees those);
3. append them under `=== Related code from the repository (context only: do not review it) ===`.

A change calling `validator.isValid(jwt)` now arrives with the `TokenValidator` class itself. The prompt tells the model to use this section for understanding only and never report findings on it; findings on files outside the chunk are dropped anyway (Level 7's validation).

RAG is a **bonus, never a dependency**:
- no index → no context;
- if finding context fails → the review goes on without it (logged).

### A bug found while checking: stale locks after a crash

Background jobs hold a Redis lock with a TTL (Level 5's pattern). While checking this level, a killed server process left an index lock behind, so **Index** silently did nothing for up to 15 minutes while the page said "failed". Two fixes, for all jobs:
- **`StaleLockCleaner`:** at startup this server runs no job yet, so every `lock:*` key is a leftover from a dead process; remove them (SCAN, not KEYS, so Redis isn't blocked).
  - Startup listeners now run in a defined **order**: clean locks → mark interrupted jobs failed → demo seeder starts new jobs.
  - It's a setting (`app.startup.clear-stale-locks`, on by default), off in tests, whose Spring contexts share one Redis.
  - With several server instances, locks would need an owner id instead.
- **A held lock is now a 409** ("already being indexed") instead of a silent no-op.

---

## 2. Database: `V11__code_index.sql`

- **Drops `vector_store`** (Spring AI's table, only used by the old test endpoint).
- **`code_chunks`:**
  - `repository_id` → `github_repositories` (cascade delete);
  - `path`, `start_line`, `end_line`, `language`, `content`;
  - `content_hash CHAR(64)` (the cache key);
  - **`embedding vector(384)`**.
- **Indexes:** `(repository_id)` for per-repository work, and **HNSW `vector_cosine_ops`** on `embedding`.
- **`github_repositories`** gains `index_status` (`NEVER`/`RUNNING`/`SUCCEEDED`/`FAILED`, with a `CHECK`), `indexed_at`, `indexed_commit_sha`, `indexed_files`, `indexed_chunks`, `index_error`.
- **`reviews`** gains `context_chunks`.

---

## 3. Backend, class by class

### 3.1 The pure part: package `codeindex`

- **`SourceFile(path, content)`** and **`CodeChunk(path, startLine, endLine, language, content)`**. `CodeChunk.embeddingText()` returns `"File: " + path + "\n" + content`.
- **`SourceArchive.read(zip)`**:
  - strips GitHub's top folder (`owner-repo-1a2b3c/`);
  - applies the extension allowlist (`LANGUAGES`, which also names the language) and the skipped folders and suffixes;
  - **`readAtMost`** reads an entry only up to the limit, so oversized entries are dropped without loading them;
  - `looksBinary` checks for a zero byte in the first 8,000 bytes;
  - enforces the file and total limits.
- **`CodeChunker.chunk(file)`** grows a chunk line by line until 40 lines or 1,000 characters.
  - **`preferBlankLine`** moves the end to the last blank line in the chunk's second half.
  - The next chunk starts 3 lines earlier.
  - Lines over 300 characters (minified code) are cut. A file's final newline doesn't count as an extra line, so line numbers and GitHub links stay exact.

### 3.2 `service/EmbeddingService` + impl

- **`embedAll(texts)`**:
  - computes the cache keys and fetches them all with one `MGET` (`RedisService.getAll`);
  - embeds the misses in **batches of 64** with Spring AI's `EmbeddingModel`;
  - caches each new vector for 30 days;
  - returns `Result(vectors, fromCache)`.
- `embed(text)` is a one-text shortcut.
- `encode`/`decode` handle the float32 little-endian Base64 format; `sha256` is reused for `content_hash`.

### 3.3 `repository/CodeChunkRepository` (JdbcTemplate)

- **`replaceAll(repositoryId, chunks)`**: delete, then `batchUpdate` inserts in batches of 500, the vector sent as text and cast with `?::vector`. Called inside the job's transaction.
- **`search(userId, repositoryId?, vector, limit)`**: the query from section 1, joined to the account so only the user's repositories match, with an optional repository filter. Two SQL variants instead of a nullable parameter.
- **`nearest(repositoryId, vector, limit)`**: the same for one repository, used for review context.
- **`vectorLiteral`**: pgvector's text format `[1.0,2.5,-0.3]`.

### 3.4 `service/CodeIndexService` + impl

- **`requestIndex(userId, repositoryId)`**: 404 for someone else's repository, 409 without an installation, 409 if already running; otherwise **`start`** → 202.
- **`start(repositoryId)`**: takes `lock:code-index:{id}` (`SET NX`, 15-minute safety TTL), marks RUNNING, and hands **`run`** to the virtual-thread executor. Returns false if the lock is held.
- **`run`**:
  1. reads the target (GitHub id, default branch, installation, demo?);
  2. demo accounts read **`demo/shop-api-source.zip`**; everyone else: installation token → head commit → zipball;
  3. `SourceArchive` → `CodeChunker` (capped at 20,000 chunks) → `embedAll` → one transaction for `replaceAll` plus the repository's counts and SUCCEEDED;
  4. logs files, chunks, cache hits and duration.

  GitHub failures invalidate the cached token and say "Couldn't download the repository from GitHub"; other failures get a generic message. The lock is released in `finally`.
- **`failInterruptedIndexes`** (startup, ordered after the lock cleaner) marks RUNNING jobs FAILED.

### 3.5 `service/CodeSearchService` + impl

- **`search(userId, query, repositoryId, limit)`**:
  - validates the query (non-blank, ≤ 500 characters → 400) and ownership of `repositoryId` (404);
  - embeds the query and runs `CodeChunkRepository.search` with limit 1–20;
  - rounds scores to 3 decimals and builds the GitHub link: `html_url/blob/<indexed commit>/<path>#L<start>-L<end>`.

  Pinning the link to the *indexed* commit means it shows exactly what was indexed, even after new pushes. There's no link for the demo's bundled code.
- **`reviewContext(repositoryId, diffText, changedPaths)`**:
  - embeds the first 1,500 characters of the diff chunk;
  - takes the nearest 8, drops chunks of the changed files, keeps 3;
  - formats the "Related code" block.

  Returns `ReviewContext(text, snippets)`.

### 3.6 Reviews (RAG wiring)

- **`ReviewEngine.analyze(…, context)`** has a new parameter, a function from a chunk to its context.
  - Each AI request is the chunk's text plus the context.
  - `contextChunks` is summed into `Analysis`.
  - **`relatedCode`** catches failures, so a broken lookup never breaks a review.
  - The old 3-argument `analyze` (used by the Playground) passes "no context".
- **`ReviewRunner`** passes `searchService.reviewContext(...)` when the repository's index is SUCCEEDED, and saves `contextChunks` on the review.
- **`prompts/code-review.st`** explains the related-code section: context only, never report on it.

### 3.7 GitHub client

- **`getBranchHeadSha`**: `GET /repositories/{id}/branches/{branch}` → `commit.sha`, read as a Jackson 3 `JsonNode`.
- **`downloadZipball`**: `GET /repositories/{id}/zipball/{ref}` as `byte[]`.
  - GitHub answers with a redirect to a short-lived download URL, which the client follows.
  - It uses a **separate `RestClient` with a 60-second read timeout**. Every other GitHub call keeps 15 seconds: a slow download shouldn't make all calls patient.

### 3.8 Controllers and other changes

| Endpoint | What |
|---|---|
| `POST /api/github/repositories/{id}/index` | start indexing → 202 (repository with `indexStatus`) |
| `GET /api/search?q&repositoryId&limit` | semantic search → `CodeSearchResult[]` |

- **`RepositoryResponse`** gains the index fields; **`ReviewResponse`** gains `contextChunks`.
- **`RedisService`** gains `getAll` (MGET) and `deleteMatching` (SCAN + DEL).
- **`config/StaleLockCleaner`**, and `@Order` on the startup listeners.
- **The demo seeder** starts the demo repository's index when it isn't indexed.
- **Removed:** `spring-ai-starter-vector-store-pgvector`, the `spring.ai.vectorstore.*` properties, `VectorStoreService(+Impl)`, and `VectorStoreController` (`/api/vector/test`).

### 3.9 Backend tests

- **`SourceArchiveTest`** (2): code kept; docs, properties, dependencies, build output, generated, binary and oversized files skipped; top-folder stripping and language detection.
- **`CodeChunkerTest`** (4):
  - a small file is one chunk with exact line numbers;
  - 62 lines → chunks 1–21, 19–42, 40–62 (blank-line ends, 3-line overlap, ≤ 40 lines);
  - the character limit, and minified lines cut;
  - blank files produce nothing.
- **`EmbeddingServiceImplTest`** (3): only cache misses reach the model and get cached; the encode/decode round trip and the 2,048-character size; 150 misses → 3 batches.
- **`StaleLockCleanerTest`** (1).
- **`CodeIndexIntegrationTest`** (6), over WireMock GitHub with real zips, the real model and real pgvector:
  - index → SUCCEEDED, commit, 3 files / 3 chunks; meaning-based search ("JWT tokens checked" → `TokenValidator`), the GitHub link, scores, the repository filter;
  - re-index after a deletion → embeddings from the cache, the old chunk gone;
  - a download failure → FAILED with the message;
  - ownership: another user finds nothing and gets 404s; a blank or over-long query → 400;
  - **RAG**: a review of an indexed repository reports 3 context chunks;
  - **DevPulse's own `src/main/java`** → "where are JWT tokens validated" has `JwtAuthenticationFilter`/`JwtService` in the top 3.
- **`DemoIntegrationTest`** (+1): the demo's code is indexed at startup (9 files), and "how is a session token checked" finds `SessionTokens`/`SessionFilter` with no GitHub link.
- **Updated:** `ReviewRunnerTest` (a search-service mock and a repository on the PR), `BackendApplicationIntegrationTest` (migrations 1–11), test properties (lock cleaner off).

**Totals:** 221 backend tests; `mvn verify` exits 0; instruction coverage **93.5%**.

---

## 4. Frontend, file by file

### 4.1 `features/repositories/CodeIndexCard.tsx` (+ api and hooks)

- **`indexRepository(id)`**, and **`useIndexRepository`**, which puts the answer (status RUNNING) straight into the cached repository.
- **`useRepository`** now polls every 2 seconds while `indexStatus` is RUNNING, then stops by itself.
- **The card:**
  - **Index code** / **Re-index** (disabled while running);
  - the state: "Indexing…", "42 files · 130 chunks · indexed 2 minutes ago · commit c0ffee1", or "Not indexed yet.";
  - the error alert for a failed index, and a **Search this repository** link (`/search?repo=<id>`).

  It sits on the repository page above the pull requests.

### 4.2 `features/search/api.ts`

`searchCode(query, repositoryId?)`, and **`useCodeSearch`**: enabled only for a non-empty query, with results cached for a minute per query and repository.

### 4.3 `features/search/pages/SearchPage.tsx`

- **The URL is the state:** `?q=` and `?repo=`. Searching updates the URL, and the URL drives the query.
- **The form:** a search box (max 500 characters), a repository select (only indexed ones), and **Search**. Searching runs on submit, not on every keystroke: each search embeds the query.
- **States:**
  - **Nothing indexed yet**, with a link to Repositories;
  - before searching, **example queries** as buttons;
  - loading skeletons, an error alert, **No matches**;
  - otherwise an ordered list of results.
- **`ResultCard`:**
  - rank, `path:start–end`, the repository, "62% match" (the cosine similarity), and a **GitHub** link;
  - the code with its real line numbers in a non-selectable gutter, so copying the code doesn't copy numbers.

`/search` joins `BUILT_PAGES`: **every sidebar page is now a real page**, and the layout test checks no "arrives in Level N" placeholder is left.

### 4.4 Review panel

When a review used related code, the summary line says "· 3 related code snippets as context ·".

### 4.5 Frontend tests

- **Mocks:**
  - `test/repositoriesMock.ts` gained the index fields and `POST …/index` (RUNNING, then finished on the next read);
  - `test/searchMock.ts` has `setSearchResults`, `searchResult()`, and `lastSearch()` to check what was asked.
- **`search.test.tsx`** (7):
  - index from the repository page through to "42 files · 130 chunks … commit c0ffee1" and the search link; a failed index's message;
  - search: results with path, lines, % match, numbered code and GitHub link, and the URL updated;
  - one repository plus an example query;
  - "No matches"; "Nothing indexed yet";
  - the review's context count.

**Totals:** 98 frontend tests; lint, formatting and type-check clean.

---

## 5. Verified

- **The plan's check, as a test:** DevPulse's own backend (203 files, 657 chunks, about 8 s to embed on the Mac) → "where are JWT tokens validated" → `JwtAuthenticationFilter` / `JwtService` in the top 3.
- **In the browser, on the running app** (demo account, real model, real pgvector): "how is a session token checked" → **`SessionTokens.java` first** (52% match), with the code and its line numbers.
- **Startup:**
  - Flyway applied V11 and the old `vector_store` table is gone;
  - the leftover lock from a killed process was removed at startup, and the demo index then succeeded (9 files, 11 chunks).
- **Not done here:** indexing your real repositories through GitHub's zipball. It uses the same installation token as the syncs; press **Index code** on a repository during the end-to-end pass.

---

## 6. Explain it in an interview

**How does your semantic code search work?**
I split each repository's source into chunks of up to 40 lines, embed each chunk with a local sentence-embedding model (384 numbers), and store the vectors in Postgres with pgvector. A search embeds the question with the same model and asks for the nearest vectors by cosine distance, using an HNSW index for approximate nearest-neighbour search. It finds code by meaning: "where are JWT tokens validated" matches `JwtService` without sharing those words.

**Why pgvector instead of a dedicated vector database?**
The data is relational: chunks belong to repositories, which belong to users. With pgvector it's one join to scope a search to the user's repositories, one transaction to replace an index atomically, and cascade deletes for free, all with the database and backups we already have. A dedicated vector DB is worth it at a much larger scale; here it would add a system to run and keep in sync.

**How do you avoid paying to re-embed unchanged code?**
Embeddings are cached in Redis by the SHA-256 of the exact text embedded, with the model name in the key. A re-index after a small commit only embeds the changed chunks. The cache is read with one MGET, misses are embedded in batches, and vectors are stored as packed float32 (2 KB) instead of JSON.

**What is RAG, and how do your reviews use it?**
Retrieval-Augmented Generation: retrieve relevant information and put it in the prompt. For each part of a diff I retrieve the three most related chunks from the repository's index (excluding the files being changed) and add them as "context only" code. The model sees how the changed code is used elsewhere. It's optional and fail-safe: without an index, or if retrieval fails, the review proceeds without context.

**How do you safely process a repository archive from the internet?**
I read GitHub's zipball as a stream with hard limits: per-file size (stop reading past 200 KB), number of files, and total bytes. A zip bomb can't exhaust memory. Only an allowlist of source extensions is read, binaries are skipped by a zero-byte check, and nothing is written to disk, so path tricks in entry names don't matter.

**Tell me about a bug you found in this level.**
A server killed mid-job left its Redis lock behind; with a 15-minute safety TTL, "Index" silently did nothing while the UI showed the last failure. I added a startup cleaner that removes all job locks (at startup, a single instance can't have running jobs), ordered the startup listeners so cleanup runs before anything starts new jobs, and made a held lock return 409 instead of silently succeeding. With multiple instances, locks would need owner ids and heartbeats instead.

**How do you know the search is good enough?**
There's a test that indexes DevPulse's own source and asserts the right files come back in the top 3 for a real question. It turns a fuzzy quality goal into a regression test, so changing the chunker or the model can't silently make search worse.

---

## 7. Files in this level

**Backend, new:**
- `V11__code_index.sql`
- `codeindex/{SourceFile, SourceArchive, CodeChunk, CodeChunker}`
- `service/{EmbeddingService, CodeIndexService, CodeSearchService}` + impls
- `repository/CodeChunkRepository`
- `controller/CodeSearchController`
- `dto/search/CodeSearchResult`
- `config/StaleLockCleaner`
- `resources/demo/shop-api-source.zip`
- tests: `codeindex/{Zips, SourceArchiveTest, CodeChunkerTest}`, `EmbeddingServiceImplTest`, `StaleLockCleanerTest`, `CodeIndexIntegrationTest`

**Backend, changed:**
- `entity/{GitHubRepository, Review, SyncStatus}`, `dto/github/RepositoryResponse`, `dto/review/ReviewResponse`
- `service/impl/{ReviewEngine, ReviewRunner, DemoServiceImpl, RepositorySyncServiceImpl}`
- `controller/GitHubRepositoryController`
- `client/GitHubApiClient(+Impl)`
- `repository/GitHubRepositoryRepository`
- `service/RedisService(+Impl)`, `common/RedisKeys`
- `prompts/code-review.st`, `application.properties`, `pom.xml`
- tests: `ReviewRunnerTest`, `DemoIntegrationTest`, `BackendApplicationIntegrationTest`, `application-test.properties`

**Backend, deleted:** `service/VectorStoreService` + impl, `controller/VectorStoreController`, the pgvector vector-store starter

**Frontend, new:**
- `features/repositories/CodeIndexCard.tsx`
- `features/search/{api.ts, pages/SearchPage.tsx, search.test.tsx}`
- `test/searchMock.ts`

**Frontend, changed:**
- `features/repositories/{api.ts, hooks.ts, pages/RepositoryDetailPage.tsx}`
- `features/reviews/ReviewPanel.tsx`
- `app/routes.tsx`
- `lib/api/schema.d.ts`
- `test/{repositoriesMock, reviewsMock, server, setup}.ts`
- `features/dashboard/dashboard.test.tsx`, `components/layout/AppLayout.test.tsx`

**Docs:**
- `docs/levels/LEVEL_10.md`
- `BUILD_PLAN.md`
- `IDE_GUIDE.md` (Level 10)
- `http/devpulse.http`
