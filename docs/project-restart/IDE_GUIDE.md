# IDE Guide — learn as we build

**IntelliJ IDEA** for the backend (Java / Spring Boot), **VS Code** for the frontend (React / TypeScript).
Each level adds a section with the IDE skills that level needs, plus a few exercises. Shortcuts are for macOS.

---

## Level 1 — Set up both IDEs and find your way around

### IntelliJ IDEA (backend)

**1. Open the project**
- *File → Open…* → choose `devpulse-ai-backend` (the folder with `pom.xml`) → *Open as Project*.
- IntelliJ sees `pom.xml` and imports it as a Maven project. Wait for the progress bar at the bottom to finish ("Indexing…").

**2. Point it at Java 21**
- *File → Project Structure…* (`⌘;`) → *Project* → *SDK* → *Add SDK → JDK…* → select
  `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`.
- Set *Language level* to **21**.

**3. Lombok**
- The code uses Lombok (`@Data`, `@Slf4j`…). The Lombok plugin is bundled; if IntelliJ asks to *Enable annotation processing*, click **Enable**.

**4. Create a run configuration for the backend**
- Open `BackendApplication.java` (`⌘O` → type `BackendApp` → Enter).
- Click the green ▶ next to `public static void main` → *Modify Run Configuration…*
  - Name: **Backend**
  - Working directory: the repo root (`$PROJECT_DIR$`) — the app reads `.env` from here.
- Run it with `⌃R`, stop it with `⌘F2`. The log appears in the *Run* tool window.
- Before running from IntelliJ, stop any backend started from a terminal (only one can use port 8080).

**5. Run the tests**
- Open `WebFoundationIntegrationTest.java`, click the ▶ in the gutter next to the class → *Run*.
- Right-click `src/test/java` → *More Run/Debug → Run 'All Tests' with Coverage* to see covered lines highlighted green/red in the editor.

**Shortcuts to learn this level**

| Shortcut | Does |
|---|---|
| `⇧⇧` (Shift twice) | Search everywhere: files, classes, settings, actions |
| `⌘O` / `⌘⇧O` | Go to class / go to file |
| `⌘E` | Recent files |
| `⌘B` (or ⌘-click) | Go to the definition of what's under the cursor |
| `⌥F7` | Find usages |
| `⌘1` | Show/hide the project tree |
| `⌃R` / `⌃⇧R` | Run the last configuration / run the test under the cursor |
| `⌥⏎` | Quick fix (add import, fix error…) |
| `⌘⌥L` | Reformat the file |

**Exercises**
1. Use `⇧⇧` to find `SecurityConfig`. Find the `corsConfigurationSource` method and `⌘B` into `CorsProperties`.
2. Put the cursor on `ErrorResponse` and press `⌥F7`. Which classes build error responses?
3. Run the **Backend** configuration, then open http://localhost:8080/swagger-ui.html and try `POST /api/auth/register` with *Try it out*.
4. Run `WebFoundationIntegrationTest` with coverage. Open `GlobalExceptionHandler` and see which handlers are still red (not yet tested).

### VS Code (frontend)

**1. Open the frontend folder**
- *File → Open Folder…* → choose `devpulse-ai-backend/frontend` (open `frontend`, not the repo root, so VS Code picks up its settings).
- Optional: *⌘⇧P → "Shell Command: Install 'code' command in PATH"*, then `code frontend` from a terminal opens it.

**2. Install the recommended extensions**
- VS Code shows *"Do you want to install the recommended extensions?"* → **Install**. Or `⌘⇧X` → filter `@recommended`.
  - **Oxc**: lint errors underlined as you type
  - **Prettier**: formats on save (already switched on in `.vscode/settings.json`)
  - **Tailwind CSS IntelliSense**: autocomplete and colour previews for class names
  - **Vitest**: run and debug tests from the *Testing* panel
- When asked *"Use workspace TypeScript version?"* → **Allow**, so the editor uses the project's TypeScript 5.9.

**3. Run the dev server inside VS Code**
- Open the terminal with `` ⌃` `` → `npm run dev` → `⌘`-click `http://localhost:5173`.
- Or use the *NPM Scripts* section at the bottom of the Explorer to run any script with one click.

**4. Run the tests**
- Click the flask icon (*Testing*) in the left bar → ▶ next to `App shell`. Failures show inline in the test file.

**Shortcuts to learn this level**

| Shortcut | Does |
|---|---|
| `⌘P` | Quick open a file by name |
| `⌘⇧P` | Command palette: every command |
| `F12` / `⇧F12` | Go to definition / find all references |
| `F2` | Rename a symbol everywhere |
| `⌘.` | Quick fix (add missing import…) |
| `` ⌃` `` | Toggle the terminal |
| `⌘B` | Toggle the sidebar |
| `⌘D` | Select the next match of the current word (multi-cursor) |
| `⌥↑` / `⌥↓` | Move the current line up/down |

**Exercises**
1. `⌘P` → `navigation.ts`. Change the Dashboard `description` text and save — the browser updates instantly (hot reload), without losing state.
2. In `AppLayout.tsx`, put the cursor on `SidebarNav` and press `F12`, then `⇧F12` to see where it's used.
3. In any `className`, start typing `bg-` and look at the Tailwind autocomplete with colour swatches.
4. In `AppLayout.test.tsx`, break a test on purpose (change `'API online'` to `'API on'`), watch it fail in the *Testing* panel, then undo with `⌘Z`.

---

## Level 2 — Debugging: watch the code run

Reading code tells you what *should* happen; a debugger shows what *does*. Level 2's auth flow is the perfect thing to step through.

### IntelliJ: debug the backend

**1. Start in debug mode**
- Stop the backend if it's running, then pick the **Backend** run configuration and click the bug icon (or `⌃D`). The log shows in the *Debug* tool window instead of *Run*.
- Code changes still need a rebuild: `⌘F9` (*Build Project*) recompiles, and Spring DevTools restarts the app.

**2. Set breakpoints**
- Click in the gutter next to a line number (or `⌘F8` on the current line). A red dot appears.
- Right-click the dot → add a **Condition** (e.g. `request.getEmail().startsWith("e2e")`) so it only stops when it matters.

**3. When it stops**

| Shortcut | Does |
|---|---|
| `F8` | Step over (run this line, stop on the next) |
| `F7` | Step into the method called on this line |
| `⇧F8` | Step out (finish this method, stop in the caller) |
| `⌥⌘R` | Resume until the next breakpoint |
| `⌥F8` | Evaluate expression: run any Java against the paused state |
| `⌘F8` | Toggle breakpoint |

The *Variables* pane shows every local value; *Frames* shows how you got here (the call stack — click a frame to look at the caller).

**4. Debug a test**
- In any test file, click the gutter ▶ → *Debug*. Same breakpoints, no browser needed.

**Exercises**
1. Breakpoint on the first line of `AuthServiceImpl.login`. Sign in from the browser. Step with `F8` and watch `user` and `passwordMatches` in *Variables*. Try a wrong password and an unknown email: see `dummyPasswordHash` being used.
2. Breakpoint in `RefreshTokenServiceImpl.rotate`. Reload the app in the browser (a reload calls `/api/auth/refresh`). Inspect `current`: its `familyId`, `expiresAt`, `revokedAt`. Then `F7` into `create(...)` and see the new token being made.
3. While paused in `rotate`, press `⌥F8` and evaluate `hash("hello")`. That's the SHA-256 that ends up in the database.
4. Breakpoint in `JwtAuthenticationFilter.authenticate`. Debug `AuthFlowIntegrationTest.expiredTokenIs401` and step until the `catch (InvalidTokenException e)` branch: this is why a bad token gives a 401 and not a 500.

### Browser DevTools: see the traffic

Open with `⌥⌘I` in Chrome.

- **Network** tab → filter `auth`. Reload the app: one `refresh` request. Click it → *Headers* shows `Set-Cookie: devpulse_refresh=…; HttpOnly; SameSite=Strict`.
- **Application** tab → *Cookies* → `http://localhost:8080`: the cookie is there with *HttpOnly* ticked. In the **Console**, `document.cookie` returns `""` — JavaScript can't read it, which is the point.
- Sign in with a wrong password: the `login` request is a 401 and its *Response* is the JSON `ErrorResponse`.

### VS Code: debug the frontend

**1. Breakpoints in tests (easiest)**
- *Testing* panel → hover a test → the *Debug Test* icon. Set a breakpoint by clicking the gutter in any `src/` file first.

**2. Breakpoints in the running app**
- With `npm run dev` running: *Run and Debug* (`⇧⌘D`) → **Debug frontend in Chrome** → ▶ (or `F5`). VS Code opens its own Chrome window attached to the debugger.
- Set a breakpoint in `src/lib/api/client.ts` inside `authFetch`, then use the app.

| Shortcut | Does |
|---|---|
| `F5` | Start / continue |
| `F10` | Step over |
| `F11` | Step into |
| `⇧F11` | Step out |
| `F9` | Toggle breakpoint |

The *Variables* and *Call Stack* panes work like IntelliJ's. In the *Debug Console* you can type any expression, e.g. `request.url`.

**Exercises**
1. In `src/features/auth/session.ts`, put one breakpoint on the first line of `refreshSession` and one inside `requestRefresh`. Start **Debug frontend in Chrome** and reload: `refreshSession` is hit twice (React StrictMode runs the start-up effect twice in development) but `requestRefresh` only once. Look at `refreshInFlight` on the second hit to see why.
2. Breakpoint on `const refreshed = await refreshSession()` in `authFetch`. Debug the test *"refreshes an expired access token and retries the request once"* in `client.test.ts` and step through the 401 → refresh → retry.
3. In `LoginPage.tsx`, breakpoint inside `onSubmit`. Submit an empty form: it never stops. Why? (Hint: `zodResolver` validates before `onSubmit` runs.)

---

## Level 3 — Refactoring safely and reading history

Level 3 replaced `UserService` with `ProfileService`, extracted `EmailAddresses.normalize`, and pulled the field-error code out of `RegisterPage` into `applyServerFieldErrors`. That is everyday refactoring: let the IDE do it, never search-and-replace by hand.

### IntelliJ: refactoring

All refactorings are under `⌃T` (*Refactor This*), which shows only what makes sense where your cursor is.

| Shortcut | Refactoring | What it does |
|---|---|---|
| `⇧F6` | Rename | Renames a class, method, variable or file and every usage (including tests and Spring bean names) |
| `⌘F6` | Change Signature | Add, remove or reorder parameters; updates every caller |
| `⌥⌘M` | Extract Method | Turns the selected lines into a new method |
| `⌥⌘V` | Extract Variable | Names the selected expression |
| `⌥⌘C` | Extract Constant | e.g. turns `"An account with this email already exists"` into a `static final` |
| `⌥⌘N` | Inline | The opposite of extract |
| `⌘⌫` | Safe Delete | Deletes only if nothing uses it, or shows you what does |
| `F6` | Move | Moves a class to another package and fixes imports |

Before any refactoring IntelliJ may show a **preview** of every change; review it, then *Do Refactor*. `⌘Z` undoes the whole refactoring at once.

**Find things**

| Shortcut | Does |
|---|---|
| `⇧⌘F` | Find in files (text search across the project) |
| `⌘F12` | File structure: jump to a method in the current file |
| `⇧⌘E` | Recent locations: places you were editing, with a code preview |
| `⌥⌘B` | Go to implementation (from `ProfileService` to `ProfileServiceImpl`) |

### IntelliJ: Git history

- **Annotate (blame):** right-click the line-number gutter → *Annotate with Git Blame*. Each line shows the commit and author; click one to see that commit.
- **File history:** right-click a file → *Git → Show History*.
- **Compare:** right-click → *Git → Compare with Branch…* (e.g. with `main`).
- **Local History:** right-click → *Local History → Show History*. IntelliJ keeps its own history of every save, even uncommitted. A safety net if you ever lose work.

**Exercises**
1. Open `ProfileServiceImpl.updateProfile`. Select the `if (!newEmail.equals(user.getEmail())) { … }` block and press `⌥⌘M` to extract it into `changeEmail(user, newEmail, currentPassword)`. Run `ProfileServiceImplTest` (`⌃⇧R` in the test file): still green. Then `⌘Z` to undo, or keep it if you like it.
2. In `AuthServiceImpl`, put the cursor on `endAllSessions` and press `⇧F6` → rename it to `signOutAllDevices`. Watch the preview update every caller. Undo with `⌘Z`.
3. Put the cursor on `ProfileService` (the interface) and press `⌥⌘B` to jump to its implementation.
4. Open `SecurityConfig.java` → *Annotate with Git Blame*. Which lines came from the Level 1 commit, which from Level 2?
5. Right-click `JwtAuthenticationFilter.java` → *Git → Show History*. Click each commit to see how the filter evolved from "throws a 500" to "never rejects".

### VS Code: refactoring and search

| Shortcut | Does |
|---|---|
| `F2` | Rename symbol everywhere (across files, imports included) |
| `⌃⇧R` | Refactor menu: extract function/constant, move to a new file… |
| `⌘.` | Quick fix (also offers refactors for the selection) |
| `⇧⌘F` | Search across files (with regex and include/exclude patterns) |
| `⌘T` | Go to a symbol anywhere in the project |
| `⇧⌘O` | Go to a symbol in the current file |
| `⌃-` | Go back to where you were |

**Git in VS Code:** the *Timeline* panel at the bottom of the Explorer shows a file's commit history; click an entry for its diff. In the Source Control view, right-click a changed file → *Open Changes*.

**Exercises**
1. Open `src/lib/forms.ts`, put the cursor on `applyServerFieldErrors` and press `F2` → rename it to `showServerFieldErrors`. Four files change (`forms.ts`, `RegisterPage.tsx` and the two settings pages). Run the tests from the *Testing* panel, then `⌘Z` in each file (or *Source Control → Discard Changes*).
2. `⌘T` → type `SettingsLayout` → Enter. Then `⇧⌘O` to see its parts.
3. In `ProfileSettingsPage.tsx`, select the date formatting `dateFormat.format(new Date(user.createdAt))`, press `⌃⇧R` → *Extract to function in module scope*, name it `memberSince`. Undo when done.
4. `⇧⌘F` → search `Session has ended`. It appears in the backend filter and in tests; that's how you find every place a message is used before changing it.

---

## Level 4 — Calling the API by hand and looking inside the database

Level 4 is about things you can't see in the UI: OAuth redirects, request headers, and encrypted columns. Three tools make them visible.

### IntelliJ HTTP Client: run API requests from a file

`http/devpulse.http` in the repo holds ready-made requests: sign in, `/me`, refresh, GitHub status, start connecting, the sign-in redirect, a bad token.

1. Open `http/devpulse.http`. At the top of the editor choose the **local** environment (it comes from `http/http-client.env.json`).
2. Replace `@email` / `@password` with your own test account (don't commit them).
3. Click the green ▶ next to **Sign in**. The response opens below; the `> {% … %}` script saves the access token into `{{accessToken}}`.
4. Run **Who am I?**, then **GitHub connection status**: both reuse the saved token.

Cookies are kept between requests like in a browser, so **refresh** works after **Sign in**. `# @no-redirect` on the GitHub request shows the raw `302` and its `Location` header instead of following it.

| Shortcut | Does |
|---|---|
| `⌃⏎` (in a request) | Run this request |
| `⌥⏎` | Convert to cURL, or add an environment variable |
| `⌘N` in a `.http` file | Insert a request template |

*Previous responses* are saved under `.idea/httpRequests/`; IntelliJ lists them in the response panel's history.

### IntelliJ Database tool: see the encrypted tokens

1. Open the **Database** tool window (right sidebar; or *View → Tool Windows → Database*).
2. **+** → **Data Source** → **PostgreSQL**. Host `localhost`, port `5433`, user and password from `POSTGRES_USER` / `POSTGRES_PASSWORD` in `.env`, database `devpulse_ai`. *Test Connection* (it may offer to download the driver), then **OK**.
3. Expand `devpulse_ai → public → tables`. Double-click a table to browse it; `⌘⏎` runs SQL in a console (`⇧⌘F10` opens a new one).

If your IntelliJ subscription doesn't include the Database tool or the HTTP Client, use `psql` and `curl` in the terminal instead. Same results, just less comfortable.

**Exercises**
1. In `devpulse.http`, run **Sign in**, then **A bad token…**: you get the JSON 401 from `JsonSecurityErrorHandler`. Then run **"Sign in with GitHub"** and read the `Location` header: the GitHub authorize URL with `client_id`, `redirect_uri` and a random `state`.
2. Run **Sign in** twice, then in the Database console:
   ```sql
   select id, family_id, revoked_at is not null as revoked, replaced_by_id from refresh_tokens order by id desc limit 5;
   ```
   Each sign-in started a new family; run **refresh** and look again to see the rotation (`replaced_by_id`).
3. After connecting GitHub (end of this level), run:
   ```sql
   select username, left(access_token, 20) as stored_token, installation_id from github_accounts;
   ```
   `stored_token` starts with `v1:` and is unreadable: that's `TokenEncryptor` at work. The real token never leaves the backend.
4. Set a breakpoint in `GitHubConnectionServiceImpl.processCallback`, start the backend in debug mode (`⌃D`), and click **Connect GitHub** in the app. Step through `fetchIdentity` and the `switch` on the flow.

### Browser DevTools: follow the OAuth redirects

1. Open DevTools (`⌥⌘I`) → **Network** → tick **Preserve log** (otherwise the log is cleared on every redirect).
2. Click **Continue with GitHub** on the sign-in page.
3. You'll see the chain: `localhost:8080/api/auth/github` (302) → `github.com/login/oauth/authorize` → (after you approve) `localhost:8080/api/github/oauth/callback` (302, with a `Set-Cookie`) → `localhost:5173/github/callback?result=signed-in` → `POST /api/auth/refresh` (200).
4. Click the callback request → *Headers*: the `Location` and `Set-Cookie: devpulse_refresh=…; HttpOnly; SameSite=Strict`.

### VS Code: find your way around a feature

| Shortcut | Does |
|---|---|
| `⌘P` then `@` | Symbols in a file (e.g. `⌘P` → `GitHubSettingsPage`, then `@Disconnect`) |
| `⌥⇧F12` | Find all references, in a side panel |
| `⌘K ⌘0` / `⌘K ⌘J` | Fold all / unfold all (to see a big file's outline) |
| `⌃G` | Go to line |

**Exercise:** in `src/features/settings/pages/GitHubSettingsPage.tsx`, press `⌘K ⌘0` to fold everything. The file reads as its outline: `GitHubSettingsPage`, `useGitHubRedirect`, `ConnectCard`, `ConnectedCards`, `DisconnectButton`. Unfold one at a time.

---

## Level 5 — Tests, background work and caches

Level 5 adds work that happens *off* the request (a background sync on virtual threads), a cache in Redis, and a lot of tests. These are the tools for each.

### IntelliJ: running tests efficiently

| Action | How |
|---|---|
| Run one test / one class | Gutter ▶ next to the method / class, or `⌃⇧R` with the cursor inside |
| Run everything under a folder | Right-click `src/test/java` (or a package) → *Run Tests* |
| Re-run only the failures | In the Run window: the *Rerun Failed Tests* button |
| Run with coverage | Gutter ▶ → *Run with Coverage*; covered lines turn green, missed ones red, and the *Coverage* tool window shows % per class |
| Jump from a failure to its line | Click the stack-trace link in the test output |
| Toggle between a class and its test | `⇧⌘T` (*Go to Test*) |

**Debugging background work**
- Put a breakpoint in `RepositorySyncServiceImpl.run`. Right-click the red dot and set **Suspend: Thread** (not *All*), so only the sync thread stops while the server keeps answering requests.
- When it stops, the *Threads & Variables* view lists the thread as `virtual-…`: that's a Java 21 virtual thread from `BackgroundConfig`.

### IntelliJ Services: your Docker containers
*View → Tool Windows → Services* (`⌘8`) → **Docker** (connect if asked). You'll see `devpulse-dev-postgres-1` and `devpulse-dev-redis-1`: view their logs, restart them, or open a terminal inside (*Create Terminal*).

### Redis from the terminal

```bash
redis-cli --scan --pattern 'cache:github:*'
```

```bash
redis-cli ttl cache:github:installation-token:<installation id>
```

The installation token is stored **encrypted** (`v1:…`) and its TTL counts down from about 55 minutes; that's how often DevPulse asks GitHub for a new one. Keys starting with `lock:` and `cooldown:` exist only while a sync runs and for a minute after a manual sync.

### VS Code: Vitest in watch mode

```bash
npm run test:watch
```

Tests re-run on every save. In the watch screen press `t` and type part of a test name (e.g. `optimistic`) to run only matching tests, `a` for all, and `q` to quit.

```bash
npm run test:coverage
```

This writes an HTML report to `coverage/index.html`; open it in a browser to see which lines tests never reach.

### Browser: TanStack Query Devtools

In development there's a small **flower icon in the bottom-left corner** of the app. Click it to open the query cache:
- Each row is a query key, e.g. `["repositories","list",{…}]`, `["repositories","sync"]`, `["github-connection"]`.
- Colours: **fresh** (green), **fetching** (blue), **stale** (yellow), **inactive** (grey).
- Click a query to see its data, when it was last updated, and buttons to refetch or invalidate it.

It isn't included in production builds.

**Exercises**
1. Open the Devtools on **Repositories**, then click **Sync now**. Watch `["repositories","sync"]` turn blue every 1.5 s (polling while `RUNNING`), then the `list` queries refetch when it finishes (`useSyncStatus`).
2. Toggle **AI review** on a repository and watch the list query's data change *before* the request finishes (optimistic update, `useToggleReview`), then refetch after.
3. In IntelliJ, run `RepositoryIntegrationTest` with coverage, then open `RepositorySyncServiceImpl`. Which lines are red? They're the paths no test reaches yet, such as the app being uninstalled while a sync is starting.
4. Debug `RepositoryIntegrationTest.connectingGitHubSyncs…` with a *Suspend: Thread* breakpoint in `apply(...)`. Inspect `remote` (what GitHub "returned") and `existing` (what was in the database).
5. With the backend running, click **Sync now** in the app, then within a minute run `redis-cli --scan --pattern 'cooldown:*'` and `redis-cli ttl` on the key you see.

---

## Reviewing and committing changes (IntelliJ)

Use IntelliJ for Git: it opens the whole repo (backend, frontend, docs) and has the best diff viewer.

### Review

1. **See everything that changed:** `⌘0` opens the **Commit** tool window.
   - *Changes* = modified files (blue). *Unversioned Files* = new files Git doesn't track yet.
   - Click the **Group By** icon → **Directory** so files are grouped by folder.
2. **Read the diffs:** double-click a file (or `⌘D`) for a side-by-side diff, old on the left, new on the right. `F7` / `⇧F7` jump to the next / previous change.
3. **Spend your attention where it matters:**

   | Read carefully (handwritten logic) | Skim (config) | Just glance (generated) |
   |---|---|---|
   | Java classes under `src/main/java` and their tests | `pom.xml`, `application.properties`, `.env.example`, `ci.yml` | `frontend/src/components/ui/*` (shadcn) |
   | `frontend/src/app`, `components/layout`, `components/common`, `features`, `pages`, `test` | `vite.config.ts`, `tsconfig*.json`, lint/format configs | `frontend/src/lib/api/schema.d.ts`, `package-lock.json` |

4. **Understand, don't just scan:** `⌘B` to jump to definitions, `⌥F7` for usages. Ask about anything unclear — you should be able to explain every line.
5. **Safety checks before every commit:**
   - `.env` and `frontend/.env.local` are **not** in the list (they hold secrets and are gitignored).
   - No `node_modules/`, `target/`, `dist/`.
   - If you edited anything while reviewing, re-run the tests (right-click `src/test/java` → *Run 'All Tests'*; `npm run check` in `frontend/`).

### Commit

1. In the Commit window, **untick everything**, then tick only the files for one commit (new files need ticking too; that adds them to Git).
2. Click the **gear** next to *Commit* and turn **off** *Reformat code*, *Rearrange code* and *Optimize imports* (they can change files you just reviewed). *Analyze code* can stay on.
3. Write the message:
   ```
   type(scope): short summary

   - detail
   - detail
   ```
   Types: `feat`, `fix`, `test`, `docs`, `chore`, `refactor`. Scope: `backend`, `frontend`, …
4. **Commit** (`⌘⏎`). Repeat for the next group of files.
5. **Check:** `⌘9` → **Log** tab shows the new commits; click one to see its files.
6. **Push (optional):** `⇧⌘K` → review → **Push**. The first time, choose *Log In via GitHub*. Pushing runs CI on GitHub.

**Example — Level 1 as three commits**

| Commit | Files | Message |
|---|---|---|
| 1 | `pom.xml`, `.env.example`, `application.properties`, `src/main/java/**`, `WebFoundationIntegrationTest.java` | `feat(backend): web foundation for the frontend` |
| 2 | `frontend/` | `feat(frontend): React + TypeScript app shell` |
| 3 | `.github/workflows/ci.yml`, `docs/` | `chore: frontend CI job, local dev guide, build plan, IDE guide` |

### VS Code alternative

`⌃⇧G` opens **Source Control** (it finds the repo even with only `frontend/` open). Click a file for its diff, **+** to stage it, type the message, `⌘⏎` to commit, **Sync Changes** to push.

### Habits

- Review before every commit, even small ones.
- One idea per commit.
- Committed by mistake and not pushed yet? Git Log → right-click the commit → **Undo Commit**. The changes come back, nothing is lost.
