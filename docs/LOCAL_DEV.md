# Running DevPulse AI Locally

Three pieces run on your Mac:

| Piece | Where | URL |
|---|---|---|
| PostgreSQL (pgvector) + Redis | Docker (`compose.dev.yaml`) | `localhost:5433`, `localhost:6379` |
| Backend (Spring Boot) | `./mvnw` or IntelliJ | http://localhost:8080 — API docs at http://localhost:8080/swagger-ui.html |
| Frontend (React + Vite) | `npm run dev` or VS Code | http://localhost:5173 |

Prerequisites: Level 0 of [BUILD_PLAN.md](project-restart/BUILD_PLAN.md) (Java 21, Node 24, Docker Desktop).

## First time only

```bash
cp .env.example .env
```
Fill in `.env` (generate secrets with `openssl rand -base64 48`).

```bash
cp frontend/.env.example frontend/.env.local
```

```bash
cd frontend && npm install
```

## Every day

1. Start Docker Desktop, then the database and cache (from the repo root):
   ```bash
   docker compose -f compose.dev.yaml up -d
   ```
2. Start the backend — in IntelliJ with the **Backend** run configuration ([IDE_GUIDE.md](project-restart/IDE_GUIDE.md)), or from the repo root:
   ```bash
   ./mvnw spring-boot:run
   ```
   Ready when the log says `Started BackendApplication`. The first start downloads the ~87 MB embedding model once.
3. Start the frontend — in VS Code's terminal, from `frontend/`:
   ```bash
   npm run dev
   ```
4. Open http://localhost:5173. The top bar shows **API online** when the frontend can reach the backend.

Stop: `Ctrl-C` the backend and frontend; `docker compose -f compose.dev.yaml stop` for the database and cache (data is kept in a Docker volume).

## Tests

Backend (from the repo root; needs Docker running — tests start their own throwaway Postgres and Redis):
```bash
./mvnw verify
```
Coverage report: `target/site/jacoco/index.html`.

Frontend (from `frontend/`):
```bash
npm run check
```

## When the backend API changes

Regenerate the frontend's TypeScript types while the backend is running (from `frontend/`):
```bash
npm run api:types
```
Commit the updated `src/lib/api/schema.d.ts`. TypeScript then flags every frontend call that no longer matches.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Top bar says **API offline** | Backend isn't running or is still starting; check its log |
| `docker: command not found` | Open a new terminal, or run `source ~/.zprofile` |
| Backend fails with `Connection refused` to 5433 | `docker compose -f compose.dev.yaml up -d` |
| Port 5173 or 8080 already in use | Another copy is running: `lsof -i :5173` (or `:8080`), then stop that process |
| Browser console shows a CORS error | The frontend origin must be listed in `APP_ALLOWED_ORIGINS` (default `http://localhost:5173`) |
