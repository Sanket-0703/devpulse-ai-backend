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
