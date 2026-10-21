# Level 1 — Project Skeletons & App Shell

**Goal:** a React app and a Spring Boot API that can talk to each other, with one consistent error format, API docs, tests, and CI. No product features yet: this is the foundation every later level builds on.

**What you can do at the end of Level 1:** open http://localhost:5173, see the DevPulse layout (sidebar, top bar, dark mode, mobile menu), and see **API online** in the top bar, which proves the browser can reach the backend.

Commits: `83716a7` (backend) · `d599f12` (frontend) · `3ce63d7` (CI + docs).

> Some Level 1 files were extended in Level 2 (routes, the API client, the error handler, `SecurityConfig`). This document explains the Level 1 part; [LEVEL_2.md](LEVEL_2.md) explains what changed.

---

## 1. The big picture

```
Browser (http://localhost:5173)                 Spring Boot (http://localhost:8080)
┌───────────────────────────────┐               ┌─────────────────────────────────────┐
│ index.html → main.tsx         │               │ Security filter chain               │
│   AppProviders                │   fetch()     │   CORS check  ─ origin allowed?     │
│     QueryClient (server data) │ ────────────► │   authorization rules               │
│     ThemeProvider             │               │   JsonSecurityErrorHandler (401/403)│
│     RouterProvider            │               │ Controllers                         │
│       AppLayout               │ ◄──────────── │ GlobalExceptionHandler (all errors) │
│         BackendStatusBadge ───┼─ GET /actuator/health                               │
│         <page>                │               │ /v3/api-docs  /swagger-ui.html       │
└───────────────────────────────┘               └─────────────────────────────────────┘
        ▲                                                       │
        └──── npm run api:types reads /v3/api-docs ─────────────┘
              → src/lib/api/schema.d.ts (TypeScript types)
```

Three ideas run through the whole level:

1. **One error shape.** Every failure, from any layer of the backend, comes back as the same JSON (`ErrorResponse`), so the frontend handles errors in one way.
2. **The backend describes itself.** springdoc generates an OpenAPI document from the controllers. The frontend generates TypeScript types from it, so a backend change that breaks the frontend shows up as a compile error.
3. **Everything is tested and checked in CI.** The backend uses JUnit and Testcontainers; the frontend uses Vitest, React Testing Library and MSW.

---

## 2. Backend

### 2.1 `pom.xml`: the build

| Change | Why |
|---|---|
| Spring Boot `3.5.6` → `3.5.16` | Latest patch of the 3.5 line: bug and security fixes, no breaking changes. It also manages Testcontainers 1.21.4, so the manual version override that was there before could be removed. |
| `springdoc-openapi-starter-webmvc-ui` 2.9.1 | Generates `/v3/api-docs` (OpenAPI JSON) and serves Swagger UI at `/swagger-ui.html` by scanning `@RestController` classes. |
| `jacoco-maven-plugin` 0.8.15 | Measures which lines the tests execute. `prepare-agent` attaches a Java agent to the test JVM; `report` (bound to the `verify` phase) writes `target/site/jacoco/index.html`. Level 1 baseline: **27%**. |

Version numbers live in `<properties>` (`springdoc.version`, `jacoco.version`) so upgrades are a one-line change.

### 2.2 `ErrorResponse`: the single error format

`dto/ErrorResponse.java`

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        Instant timestamp, int status, String error,
        String message, String path, List<FieldError> errors) {

    public record FieldError(String field, String message) { }
}
```

- **A Java `record`** is an immutable data class: the compiler generates the constructor, getters (`status()`), `equals`, `hashCode` and `toString`. Perfect for DTOs that are only created and serialized.
- **`timestamp` is an `Instant`**, serialized by Jackson as ISO-8601 UTC (`2026-10-22T10:15:30Z`), so it's unambiguous across time zones.
- **`error`** is the HTTP reason phrase ("Bad Request"); **`message`** is the human-readable explanation; **`path`** is the URL that failed.
- **`errors`** is only filled for validation failures, one entry per invalid field. `@JsonInclude(NON_NULL)` drops it from the JSON when it's `null`.
- *(Level 2 added `@Schema(requiredMode = REQUIRED)` to the fields so the generated TypeScript type marks them as always present.)*

Example:

```json
{ "timestamp": "2026-10-22T10:15:30Z", "status": 400, "error": "Bad Request",
  "message": "Validation failed", "path": "/api/auth/register",
  "errors": [{ "field": "email", "message": "Invalid email format" }] }
```

### 2.3 `GlobalExceptionHandler`: turning exceptions into that format

`exception/GlobalExceptionHandler.java`

`@RestControllerAdvice` makes this class a global interceptor for exceptions thrown by **any controller**. Spring finds the `@ExceptionHandler` method whose exception type matches most closely and uses its return value as the response.

Every handler ends in one private helper:

```java
private ResponseEntity<ErrorResponse> build(HttpStatus status, String message,
        HttpServletRequest request, List<ErrorResponse.FieldError> errors) {
    ErrorResponse body = new ErrorResponse(Instant.now(), status.value(),
            status.getReasonPhrase(), message, request.getRequestURI(), errors);
    return ResponseEntity.status(status).body(body);
}
```

(plus a 3-argument overload without `errors`). Handlers return `ResponseEntity<ErrorResponse>` rather than using `@ResponseStatus`, so the status is set in code and the same helper builds every response.

| Status | Exception(s) | When it happens | Message |
|---|---|---|---|
| 400 | `MethodArgumentNotValidException` | `@Valid` on a request body fails | "Validation failed" + one `FieldError` per field (`getBindingResult().getFieldErrors()`) |
| 400 | `ConstraintViolationException` | Validation on method parameters (`@Validated` services, path variables) | "Validation failed" + violations, field = property path |
| 400 | `HttpMessageNotReadableException` | Body missing or not valid JSON | "Request body is missing or malformed" |
| 400 | `MethodArgumentTypeMismatchException`, `MissingServletRequestParameterException` | `?page=abc`, a required query parameter missing | Spring's own message |
| 401 | `InvalidCredentialsException` | Wrong email/password | The exception's message |
| 401 | `AuthenticationException` | Spring Security exception reaching a controller | "Authentication required" |
| 403 | `AccessDeniedException` | `@PreAuthorize` fails inside a controller call | "You do not have permission to do this" |
| 404 | `ResourceNotFoundException` | Our services can't find an entity | The exception's message |
| 404 | `NoResourceFoundException` | No controller mapped to the URL | "No endpoint at this path" |
| 405 | `HttpRequestMethodNotSupportedException` | e.g. `GET` on a `POST` endpoint | Spring's message |
| 409 | `EmailAlreadyExistsException`, `ConflictException` | Duplicate data | The exception's message |
| 415 | `HttpMediaTypeNotSupportedException` | Wrong `Content-Type` | Spring's message |
| 502 | `AIServiceException` | The AI provider failed (logged at WARN) | "The AI provider could not complete the request" |
| 500 | `Exception` (catch-all) | Anything unexpected | "Something went wrong" |

**The catch-all** logs the full stack trace with `log.error(...)` (via Lombok's `@Slf4j`) but returns only a generic message. Stack traces in responses leak internals (class names, SQL, library versions) that help attackers, so they stay in the server log.

### 2.4 `JsonSecurityErrorHandler`: errors that happen before any controller

`security/JsonSecurityErrorHandler.java`

**The problem:** Spring Security runs as a chain of *servlet filters* in front of the controllers. If a request is rejected there (no token, insufficient role), no controller runs, so `@RestControllerAdvice` never sees it. By default Spring Security then answers with an **empty 403**, even for a missing login.

**The fix:** one class implements both hooks Spring Security calls:

| Interface | Method | Called when | We answer |
|---|---|---|---|
| `AuthenticationEntryPoint` | `commence(...)` | A protected URL is requested without valid authentication | 401 + `ErrorResponse` |
| `AccessDeniedHandler` | `handle(...)` | Authenticated, but not allowed | 403 + `ErrorResponse` |

Both call `write(...)`, which builds an `ErrorResponse`, sets the status and `Content-Type: application/json`, and serializes it with Spring's own `ObjectMapper` (injected, so dates are formatted exactly like in controller responses). *(Level 2 added: the 401 message comes from a request attribute set by the JWT filter, e.g. "Access token has expired".)*

### 2.5 CORS: letting the React app call the API

**What CORS is.** The browser enforces the *same-origin policy*: JavaScript from `http://localhost:5173` may not read responses from `http://localhost:8080` (different port = different origin) unless the server explicitly allows it with `Access-Control-Allow-*` headers. For anything other than a "simple" request (e.g. JSON bodies, an `Authorization` header), the browser first sends an `OPTIONS` **preflight** request asking permission.

`config/CorsProperties.java`

```java
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) { }
```

Binds `app.cors.allowed-origins` (comma-separated → `List`) from `application.properties`, which reads `APP_ALLOWED_ORIGINS` from the environment with `http://localhost:5173` as the default. Registered in `BackendApplication` via `@EnableConfigurationProperties`.

`config/SecurityConfig.java` → `corsConfigurationSource()`

```java
config.setAllowedOrigins(corsProperties.allowedOrigins());
config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
config.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));
config.setAllowCredentials(true);
config.setMaxAge(Duration.ofHours(1));
source.registerCorsConfiguration("/**", config);
```

| Setting | Meaning |
|---|---|
| Allowed origins | Exact list, never `*`. Browsers forbid `*` together with credentials anyway. |
| Allowed headers | Only what the frontend sends: `Authorization` (the token) and `Content-Type`. |
| `allowCredentials(true)` | Lets the browser send and accept cookies cross-origin. Needed in Level 2 for the refresh-token cookie. |
| `maxAge` 1 h | The browser caches a preflight result for an hour, so not every request needs an `OPTIONS` first. |
| `/**` | Applies to every path, including `/actuator/health` (the status badge calls it). |

`.cors(Customizer.withDefaults())` in the filter chain tells Spring Security to use this `CorsConfigurationSource` bean, and to answer preflights **before** authentication (a preflight never carries a token).

### 2.6 `SecurityConfig`: Level 1 changes

- `.cors(...)`, as above.
- `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html` are `permitAll()` so the docs are browsable without signing in (local development only for now).
- `.exceptionHandling(ex -> ex.authenticationEntryPoint(...).accessDeniedHandler(...))` plugs in `JsonSecurityErrorHandler`.

### 2.7 `OpenApiConfig`: API documentation

`config/OpenApiConfig.java`

- `@OpenAPIDefinition(info = @Info(title, version, description), security = @SecurityRequirement(name = "bearerAuth"))` sets the document's title and says that by default endpoints need the `bearerAuth` scheme.
- `@SecurityScheme(name = "bearerAuth", type = HTTP, scheme = "bearer", bearerFormat = "JWT")` defines that scheme. It's what makes the **Authorize** button in Swagger UI work: paste a token once and every *Try it out* call sends `Authorization: Bearer …`.
- `errorResponseSchema()` is an `OpenApiCustomizer` bean that adds the `ErrorResponse` schema to the document. springdoc only documents classes that endpoints *declare*; `ErrorResponse` is only returned from the exception handler, so without this the frontend would get no TypeScript type for errors. `ModelConverters.getInstance().readAll(ErrorResponse.class)` turns the class into schemas (including the nested `FieldError`).

### 2.8 `application.properties`

```properties
app.cors.allowed-origins=${APP_ALLOWED_ORIGINS:http://localhost:5173}
springdoc.api-docs.enabled=${API_DOCS_ENABLED:true}
springdoc.swagger-ui.enabled=${API_DOCS_ENABLED:true}
springdoc.swagger-ui.persist-authorization=true
```

`${NAME:default}` reads an environment variable (or `.env` entry) with a fallback. `persist-authorization` keeps the token you entered in Swagger UI across page reloads. `API_DOCS_ENABLED=false` turns the docs off (for a production profile later).

### 2.9 Tests: `WebFoundationIntegrationTest`

Extends `AbstractIntegrationTest`, which boots the **whole application** (`@SpringBootTest(webEnvironment = RANDOM_PORT)`) against real PostgreSQL (pgvector image) and Redis containers started by **Testcontainers**. `@ServiceConnection` wires the containers' host and port into Spring automatically. Calls go over real HTTP with `TestRestTemplate`.

| Test | Proves |
|---|---|
| `corsPreflightFromFrontendIsAllowed` | An `OPTIONS` from `http://localhost:5173` gets 200 with `Access-Control-Allow-Origin` and `…-Allow-Credentials: true` |
| `corsPreflightFromUnknownOriginIsRejected` | A preflight from `https://evil.example` gets 403 and no allow-origin header |
| `protectedEndpointWithoutTokenReturnsJson401` | `/api/profile` with no token → 401, JSON content type, `status/error/path/timestamp/message` present |
| `validationErrorListsEachInvalidField` | Invalid register body → 400, "Validation failed", `errors[]` contains `email` |
| `malformedJsonBodyReturns400NotA500` | `{not json` → 400 |
| `openApiDocsArePublicAndDescribeTheApi` | `/v3/api-docs` is public, lists the auth paths, and includes the `ErrorResponse` schema |

---

## 3. Frontend

### 3.1 Tooling and configuration

| File | What it does |
|---|---|
| `package.json` | Dependencies and scripts. `dev` (Vite dev server), `build` (`tsc -b` type-check, then `vite build`), `lint` (oxlint), `format` / `format:check` (Prettier), `typecheck`, `test` / `test:watch` / `test:coverage` (Vitest), `api:types` (generate types from the backend), `check` (everything CI runs). `allowScripts.fsevents: false`: npm 11 asks before running install scripts; `fsevents` ships prebuilt and doesn't need one. |
| `.node-version` | `24`. fnm switches to Node 24 automatically when you `cd` into `frontend/`; CI reads the same file. |
| `tsconfig.json` | Root file that only references the two real configs below (TypeScript "project references", built by `tsc -b`). |
| `tsconfig.app.json` | For `src/`: `strict: true` (null checks, no implicit `any`…), `noUnusedLocals/Parameters`, `jsx: react-jsx`, `paths: { "@/*": ["./src/*"] }` for the `@/` import alias, and the global types for Vite, Vitest and jest-dom matchers. |
| `tsconfig.node.json` | For `vite.config.ts`, which runs in Node. |
| `vite.config.ts` | Plugins: `@vitejs/plugin-react` (JSX and Fast Refresh) and `@tailwindcss/vite`. `resolve.alias` maps `@` to `src` (same as the tsconfig path). `server.port 5173` with `strictPort` (fail instead of silently moving to 5174, which CORS wouldn't allow). `test` block: jsdom environment, global `describe/it/expect`, the setup file, and coverage settings. |
| `.oxlintrc.json` | Lint rules: React hooks rules as errors, `only-export-components` as a warning (Fast Refresh only works when a file exports components only). Turned off for `src/components/ui/**`, the generated shadcn files. |
| `.prettierrc.json` | No semicolons, single quotes, trailing commas, 100-character lines, and `prettier-plugin-tailwindcss`, which sorts Tailwind classes into a consistent order. |
| `components.json` | shadcn/ui settings: the `radix-nova` style, where components and utils live, Lucide icons. Used by `npx shadcn add <component>`. |
| `.env.example` | `VITE_API_BASE_URL=http://localhost:8080`. Only `VITE_*` variables reach browser code, so secrets must never go here. |
| `.vscode/settings.json`, `extensions.json` | Format on save with Prettier, use the project's TypeScript, Tailwind autocomplete inside `cn(...)`; recommended extensions. |

**Why these libraries** (the resume says React; the rest is the standard modern React stack):

| Library | Job |
|---|---|
| Vite | Dev server with instant hot reload; production bundler |
| React Router 8 | URL → page mapping, nested layouts, redirects |
| TanStack Query | Fetching, caching and refetching *server* data (loading/error states for free) |
| Tailwind CSS 4 | Utility classes (`flex gap-2 p-4`) instead of separate CSS files |
| shadcn/ui (Radix) | Accessible components copied into the repo (`src/components/ui`), fully editable |
| react-hook-form + zod | Forms and validation (used from Level 2) |
| openapi-typescript + openapi-fetch | Types generated from the backend; a fetch client that uses them |
| Vitest + React Testing Library + MSW | Unit/component tests; MSW fakes the backend at the network level |

### 3.2 Entry point: `index.html` and `main.tsx`

`index.html` is the single HTML page. It contains `<div id="root">` (React renders into it) and loads `/src/main.tsx`. It also has a small inline `<script>` **in the `<head>`** that reads the saved theme from `localStorage` and adds the `dark` class to `<html>` before anything paints. Without it the page would flash white for a moment in dark mode while React loads.

`src/main.tsx`

```tsx
const router = createBrowserRouter(routes)
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>
  </StrictMode>,
)
```

- `createBrowserRouter(routes)` builds a router that uses real browser URLs (history API).
- `<StrictMode>`: in development only, React renders components and runs effects **twice** to surface bugs from side effects. (This matters in Level 2: the session refresh must survive it.)
- `!` tells TypeScript the element definitely exists.

### 3.3 Providers: `AppProviders`, `queryClient`, theme

`src/app/AppProviders.tsx` wraps the app in everything it needs:

```tsx
<QueryClientProvider client={queryClient}>
  <ThemeProvider>
    <TooltipProvider>
      {children}
      <Toaster richColors closeButton />
    </TooltipProvider>
  </ThemeProvider>
</QueryClientProvider>
```

- `useState(createQueryClient)` creates the client **once** per app instance (the function form of `useState` runs only on the first render), so re-renders don't throw the cache away.
- `TooltipProvider` is required by shadcn tooltips; `Toaster` (sonner) renders toast notifications anywhere in the app via `toast('…')`.
- *(Level 2 added `<AuthProvider>` around `children`.)*

`src/app/queryClient.ts`: `createQueryClient()` returns a `QueryClient` with:
- `staleTime: 30_000`: data counts as fresh for 30 s, so navigating between pages doesn't refetch everything.
- `refetchOnWindowFocus: false`: don't refetch every time you switch back to the tab (noisy during development).

It lives in its own file because a file that exports both components and plain functions breaks Fast Refresh (the lint rule above).

`src/app/theme/themeContext.ts`
- `type Theme = 'light' | 'dark' | 'system'`.
- `ThemeContext` (React context, default `null`) and `useTheme()`, which throws a clear error if used outside the provider, so a wiring mistake fails loudly instead of returning `undefined`.

`src/app/theme/ThemeProvider.tsx`

| Piece | What it does |
|---|---|
| `readStoredTheme()` | Reads `localStorage['devpulse-theme']`, validates it, defaults to `system`. Wrapped in `try/catch`: storage can be blocked (private mode). |
| `useState(readStoredTheme)` | The user's choice. |
| `systemDark` state + `matchMedia('(prefers-color-scheme: dark)')` listener | Tracks the OS setting live; the effect's cleanup removes the listener. |
| `resolvedTheme` | What is actually shown: `system` resolved to `light` or `dark`. |
| Effect on `resolvedTheme` | Toggles the `dark` class on `<html>` and sets `color-scheme` (so native scrollbars and inputs match). |
| `setTheme(next)` | Updates state and saves to `localStorage`. `useCallback` keeps its identity stable. |
| `useMemo` on the context value | Consumers only re-render when the theme actually changes. |

Why not the `next-themes` package? It injects a `<script>` element for server-rendered apps, and React 19 logs an error for that in a client-only app. The custom provider plus the `index.html` script does the same job in about 60 lines.

`src/components/layout/ThemeToggle.tsx`: a shadcn `DropdownMenu` with a `DropdownMenuRadioGroup` (Light / Dark / System) bound to `theme` / `setTheme`. The trigger button stacks a sun and a moon icon and uses `dark:` Tailwind variants to rotate and scale one out and the other in. It has `aria-label="Change theme"` because it contains only icons.

### 3.4 Routing: `routes.tsx` (Level 1 version)

```tsx
{
  path: '/', element: <AppLayout />, errorElement: <RouteErrorPage />,
  children: [
    { index: true, element: <Navigate to="/dashboard" replace /> },
    ...NAV_ITEMS.map(item => ({ path: item.to, element: <ComingSoonPage item={item} /> })),
    { path: '*', element: <NotFoundPage /> },
  ],
}
```

- **Nested routes:** `AppLayout` renders once; the matching child renders inside its `<Outlet />`.
- `index: true` matches exactly `/` and redirects to `/dashboard`. `replace` means the redirect doesn't add a history entry, so the Back button doesn't bounce.
- The nav routes are **generated from `NAV_ITEMS`**, so the sidebar and the routes can't drift apart. Each later level replaces a `ComingSoonPage` with the real page.
- `*` catches every unknown URL → 404 page, still inside the layout.
- `errorElement` is the router's error boundary: if a page throws while rendering, this renders instead of a blank screen.
- *(Level 2 wrapped this in `RequireAuth` and added public `/login` and `/register` routes.)*

### 3.5 Layout components

`src/components/layout/navigation.ts`
- `type NavItem = { to, label, icon: LucideIcon, level, description }`.
- `NAV_ITEMS`: Dashboard (Level 8), Repositories (5), Reviews (7), Code search (10), Playground (8), Settings (3). `level` is shown on the placeholder page, so the app itself shows the build plan.

`src/components/layout/SidebarNav.tsx`
- Maps `NAV_ITEMS` to `<NavLink>`. `NavLink`'s `className` can be a function receiving `{ isActive }`; the active item gets the accent background. NavLink also sets `aria-current="page"` on the active link for screen readers (the tests check this).
- `onNavigate` prop: called on click, used by the mobile drawer to close itself after you pick a page.
- `focus-visible:ring-2` shows a focus ring only for keyboard users.

`src/components/layout/Logo.tsx`: the activity icon plus "DevPulse AI" as a `<Link to="/dashboard">`.

`src/components/layout/AppLayout.tsx`

| Part | How |
|---|---|
| Two-column grid on desktop | `md:grid md:grid-cols-[15rem_1fr]`: a 15 rem sidebar plus the rest. Below the `md` breakpoint (768 px) it's a single column. |
| Skip link | `<a href="#main" class="sr-only focus:not-sr-only">Skip to content</a>`: invisible until a keyboard user tabs to it, then jumps past the navigation. |
| Desktop sidebar | `<aside class="hidden md:flex">` with `Logo` and `SidebarNav`. |
| Mobile drawer | shadcn `Sheet` (a Radix dialog sliding in from the left), opened by the menu button (`md:hidden`). Its open state is React state so `SidebarNav`'s `onNavigate` can close it. `SheetTitle` is required for accessibility (dialogs need a name). |
| Top bar | `sticky top-0` with `backdrop-blur`; the right side holds `BackendStatusBadge` and `ThemeToggle` (Level 2 added `UserMenu`). |
| Content | `<main id="main" class="max-w-6xl …"><Outlet /></main>`. |

### 3.6 Backend status: `useBackendHealth` and `BackendStatusBadge`

`src/features/system/useBackendHealth.ts`

```ts
const { data, isPending, isError } = useQuery({
  queryKey: ['backend-health'],
  queryFn: async () => {
    const response = await fetch(`${API_BASE_URL}/actuator/health`)
    if (!response.ok) throw new Error(...)
    return (await response.json()) as { status: string }
  },
  refetchInterval: 30_000,
  retry: false,
})
```

- **`useQuery`** is TanStack Query's core hook: you give it a cache key and a function that returns a promise; it handles loading, error, caching and refetching.
- `refetchInterval: 30_000` polls every 30 s, so the badge notices when you stop or start the backend.
- `retry: false`: by default failed queries retry 3 times; for a status light we want "offline" immediately.
- It uses plain `fetch` because actuator endpoints aren't in the OpenAPI docs.
- Returns `'checking' | 'online' | 'offline'`.

`src/features/system/BackendStatusBadge.tsx`: a `Badge` with a coloured dot (green / red / pulsing grey) and a label. `aria-live="polite"` makes screen readers announce changes ("API offline") without interrupting.

### 3.7 Shared building blocks

| Component | Props | Used for |
|---|---|---|
| `PageHeader` | `title`, `description?`, `actions?` | The `<h1>` and subtitle at the top of every page; `actions` holds buttons on the right (stacked on mobile). |
| `EmptyState` | `icon`, `title`, `description`, `action?` | The dashed-border box for "nothing here yet", "not found", and error screens. |

### 3.8 Pages

| Page | What it shows |
|---|---|
| `ComingSoonPage` | `PageHeader` with the nav item's label and description, plus an `EmptyState` "Not built yet — arrives in Level N". |
| `NotFoundPage` | "Page not found" with a *Back to dashboard* button. `<Button asChild><Link …/></Button>` renders a real link styled as a button (Radix `Slot`), so it's keyboard- and middle-click-friendly. |
| `RouteErrorPage` | `useRouteError()` gives whatever was thrown. `isRouteErrorResponse(error)` checks for router errors (status + text); otherwise it shows `error.message`. Offers *Reload page*. |

### 3.9 The API layer (Level 1 version)

`src/lib/api/schema.d.ts` is **generated** by `npm run api:types`, which runs `openapi-typescript http://localhost:8080/v3/api-docs`. It contains `paths` (every endpoint: its method, parameters, body and responses) and `components['schemas']` (every DTO). Never edit it by hand; regenerate it when the backend changes and commit it.

`src/lib/api/client.ts` (Level 1):

```ts
export const api = createClient<paths>({ baseUrl: API_BASE_URL })
export type ApiError = components['schemas']['ErrorResponse']
```

`openapi-fetch` uses `paths` to type-check every call: `api.POST('/api/auth/login', { body })` only compiles if that path exists, accepts POST, and `body` matches `LoginRequest`. The returned `data` / `error` are typed from the responses. *(Level 2 added the auth-aware `fetch` wrapper.)*

### 3.10 Styling

- `src/index.css`: `@import "tailwindcss"`, the shadcn base styles and the Geist font, then **design tokens** as CSS variables (`--background`, `--primary`, `--muted-foreground`, `--sidebar`…) for `:root` and `.dark`. Tailwind classes like `bg-background` or `text-muted-foreground` read these variables, which is why the whole app switches theme by toggling one class.
- `@custom-variant dark (&:is(.dark *))` makes `dark:` classes apply under the `.dark` class (not only the OS setting).
- `src/lib/utils.ts` re-exports `cn` from the `cn` package (by shadcn): it merges class names and resolves Tailwind conflicts (`cn('p-2', isBig && 'p-4')` → `p-4`).
- `src/components/ui/*`: shadcn components (button, badge, card, input, label, separator, skeleton, sheet, dropdown-menu, tooltip, avatar, sonner; Level 2 added alert). They're generated into the repo, so they're ours to edit.

### 3.11 Frontend tests

`src/test/setup.ts` runs before every test file:
- imports `@testing-library/jest-dom/vitest`, adding matchers such as `toBeInTheDocument()` and `toHaveAttribute()`;
- starts the MSW server (`onUnhandledFrame: 'error'`: any request without a mock fails the test, so a forgotten mock is obvious), resets handlers after each test, and closes the server at the end;
- defines `window.matchMedia`, which jsdom lacks and the theme provider needs.

`src/test/server.ts`: `setupServer(...handlers)` with a default handler that answers `/actuator/health` with `{ status: 'UP' }`. Tests override handlers with `server.use(...)`.

**MSW** (Mock Service Worker) intercepts `fetch` at the network level, so the code under test runs unchanged: the same URLs and the same JSON as the real backend.

`src/test/render.tsx` → `renderApp(path)`: renders the **real routes** with `createMemoryRouter(routes, { initialEntries: [path] })` (a router that keeps history in memory instead of the address bar) inside `AppProviders`, and returns `user` from `userEvent.setup()` for realistic clicks and typing.

`src/components/layout/AppLayout.test.tsx`

| Test | Checks |
|---|---|
| redirects / to the dashboard | `/` ends up at `/dashboard` with the Dashboard heading |
| navigates between pages from the sidebar | clicking *Repositories* shows its page and sets `aria-current="page"` |
| shows a 404 page for unknown routes | heading and a working *Back to dashboard* link |
| switches to dark mode and remembers the choice | `<html>` gets the `dark` class; `localStorage` stores `dark` |
| shows the API as online | default health mock → "API online" |
| shows the API as offline | `HttpResponse.error()` (network failure) → "API offline" |

Tests find elements **by role and accessible name** (`getByRole('link', { name: 'Repositories' })`), the way a user or screen reader would, rather than by CSS class. That also checks accessibility.

---

## 4. CI: `.github/workflows/ci.yml`

Runs on every push to `main`, every pull request, and on demand.

| Job | Steps |
|---|---|
| `backend` | Check out → JDK 21 with Maven cache → cache the embedding model → `./mvnw -B verify` (all tests, Testcontainers uses the runner's Docker) → upload the JaCoCo report (always) and the Surefire reports (on failure) |
| `frontend` | Check out → Node from `frontend/.node-version` with npm cache → `npm ci` (exact install from the lockfile) → `npm run check` → `npm run build` |
| `secret-scan` | gitleaks over the checked-out files with the repo's `.gitleaks.toml` |

`concurrency: ci-${{ github.ref }}` with `cancel-in-progress` cancels an older run of the same branch when you push again.

---

## 5. What happens when you open the app

1. The browser loads `index.html`; the inline script applies the saved theme.
2. `main.tsx` creates the router and renders `AppProviders` → `RouterProvider`.
3. The URL `/` matches the index route → `<Navigate to="/dashboard">`.
4. `AppLayout` renders the sidebar, top bar, and `ComingSoonPage` for Dashboard in the `Outlet`.
5. `BackendStatusBadge` mounts; `useQuery` calls `GET http://localhost:8080/actuator/health`.
6. The browser sees a cross-origin request; the backend's CORS config returns `Access-Control-Allow-Origin: http://localhost:5173`, so the browser lets the code read the response.
7. `{"status":"UP"}` → the badge turns green: **API online**. It polls again every 30 seconds.

---

## 6. Explain it in an interview

**Why one error format, and how do you guarantee it?**
So the frontend handles every failure the same way. Controller exceptions go through a `@RestControllerAdvice` with a handler per exception type plus a catch-all; errors raised in the security filter chain (before any controller) go through a custom `AuthenticationEntryPoint` and `AccessDeniedHandler`, which write the same JSON. A test asserts the shape.

**Why doesn't the catch-all return the exception message?**
It can contain internals (SQL, class names). The full trace goes to the log; the client gets "Something went wrong".

**What is CORS and how did you configure it?**
A browser rule: scripts can only read cross-origin responses the server explicitly allows. I allow exactly the frontend's origin (from configuration), only the methods and headers the frontend uses, credentials for the refresh cookie, and cache preflights for an hour. A test checks that a foreign origin is rejected.

**How do frontend and backend stay in sync?**
springdoc generates an OpenAPI document from the controllers; `openapi-typescript` turns it into TypeScript types; `openapi-fetch` uses them so every call is type-checked. Changing a DTO and regenerating makes mismatched frontend code fail to compile.

**How do you test the frontend without a backend?**
MSW intercepts `fetch` and answers with mock JSON shaped like the real API. Tests render the real routes in a memory router and interact by role and label, like a user.

**What do Testcontainers give you over an in-memory database?**
The tests run against the same PostgreSQL (with pgvector) and Redis versions as development, including Flyway migrations, so there are no "works on H2, fails on Postgres" surprises.

---

## 7. Files in this level

**Backend:** `pom.xml` · `BackendApplication.java` · `config/CorsProperties.java` · `config/OpenApiConfig.java` · `config/SecurityConfig.java` · `dto/ErrorResponse.java` · `exception/GlobalExceptionHandler.java` · `security/JsonSecurityErrorHandler.java` · `application.properties` · `.env.example` · `WebFoundationIntegrationTest.java`

**Frontend:** config (`package.json`, `tsconfig*.json`, `vite.config.ts`, `.oxlintrc.json`, `.prettierrc.json`, `components.json`, `.node-version`, `.env.example`, `.vscode/*`) · `index.html` · `src/main.tsx` · `src/index.css` · `src/app/{AppProviders,queryClient,routes}` · `src/app/theme/{ThemeProvider,themeContext}` · `src/components/layout/{AppLayout,SidebarNav,navigation,Logo,ThemeToggle}` · `src/components/common/{PageHeader,EmptyState}` · `src/components/ui/*` (generated) · `src/features/system/{useBackendHealth,BackendStatusBadge}` · `src/pages/{ComingSoonPage,NotFoundPage,RouteErrorPage}` · `src/lib/api/{client,schema.d}` · `src/lib/utils.ts` · `src/test/{setup,server,render}` · `AppLayout.test.tsx`

**CI and docs:** `.github/workflows/ci.yml` · `docs/LOCAL_DEV.md` · `docs/project-restart/BUILD_PLAN.md` · `docs/project-restart/IDE_GUIDE.md`
