# DevPulse AI — Frontend

React + TypeScript (Vite), Tailwind CSS + shadcn/ui, React Router, TanStack Query.
How to run the whole app locally: [docs/LOCAL_DEV.md](../docs/LOCAL_DEV.md).

| Command                           | What it does                                                                                                    |
| --------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| `npm run dev`                     | Dev server at http://localhost:5173 with hot reload                                                             |
| `npm test` / `npm run test:watch` | Component tests (Vitest + React Testing Library, API mocked with MSW)                                           |
| `npm run test:coverage`           | Tests + coverage report in `coverage/`                                                                          |
| `npm run check`                   | Everything CI runs: lint, format check, typecheck, tests                                                        |
| `npm run build`                   | Production build into `dist/`                                                                                   |
| `npm run format`                  | Format all files with Prettier                                                                                  |
| `npm run docs:pdf`                | Render the Markdown docs in `docs/` to PDFs in `docs/pdf/` (needs Google Chrome); pass a path to render one doc |
| `npm run api:types`               | Regenerate `src/lib/api/schema.d.ts` from the running backend's OpenAPI docs                                    |

## Folder layout

```
src/
  app/            router, providers, query client
  components/
    ui/           shadcn/ui primitives (generated: `npx shadcn@latest add <name>`)
    layout/       app shell: sidebar, top bar, theme toggle
    common/       shared building blocks (PageHeader, EmptyState)
  features/<x>/   one folder per feature: API hooks, components, pages
  pages/          top-level pages not tied to one feature (404, errors)
  lib/api/        typed API client + generated OpenAPI types
  test/           test setup, MSW mock server, render helpers
```

Import from `src` with the `@/` alias: `import { Button } from '@/components/ui/button'`.
