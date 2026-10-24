# Mac Environment Readiness

Inspected 2026-10-21. Machine: Apple Silicon (arm64), macOS 26.7.1, 16 GB RAM, ~404 GB free disk, shell `zsh` (no `~/.zshrc` or `~/.zprofile` yet).
Nothing was installed or changed during the audit.

## 1. Current state

| Tool | Needed for | Status |
|---|---|---|
| Xcode Command Line Tools | git, compilers | ✅ Installed (`/Library/Developer/CommandLineTools`) |
| Git | VCS | ✅ 2.50.1 (Apple). ⚠️ Global `user.name`/`user.email` **not set** |
| Homebrew | Package manager | ❌ Missing |
| JDK 21 | Backend build/run (`java.version=21`) | ❌ Missing. `/usr/bin/java` is only the macOS stub ("Unable to locate a Java Runtime") |
| Maven | Backend build | ➖ Not needed. The repo ships `./mvnw` |
| Docker (Desktop / OrbStack / Colima) | Local Postgres+pgvector, Redis, Testcontainers, image builds | ❌ Missing |
| Node.js LTS + npm | React frontend | ❌ Missing |
| PostgreSQL client (`psql`) | DB inspection | ❌ Missing (server should run in Docker) |
| `redis-cli` | Cache inspection | ❌ Missing (server should run in Docker) |
| AWS CLI v2 | Deployment, cost checks | ❌ Missing (`~/.aws` absent) |
| GitHub CLI (`gh`) | PRs, repo/secret admin | ❌ Missing |
| Editor | — | ✅ VS Code installed. IntelliJ IDEA not installed (optional) |

**Conclusion:** the machine can't compile or run the project yet. Every step below needs your approval. They're listed in order.

## 2. Setup commands (run yourself, or approve me to run them)

### 2.1 Homebrew
Install from the official instructions at https://brew.sh (the one-line installer asks for your password). Then add it to your shell:

```bash
echo 'eval "$(/opt/homebrew/bin/brew shellenv)"' >> ~/.zprofile
```

```bash
eval "$(/opt/homebrew/bin/brew shellenv)"
```

### 2.2 Java 21 (Temurin)
```bash
brew install --cask temurin@21
```
```bash
echo 'export JAVA_HOME=$(/usr/libexec/java_home -v 21)' >> ~/.zprofile
```
Verify:
```bash
java -version
```

### 2.3 Docker runtime
Pick one (see TECHNICAL_DECISIONS D-10). Docker Desktop is the default recommendation because Testcontainers supports it out of the box:
```bash
brew install --cask docker
```
Open Docker.app once, then give it at least 6 GB of memory (the ONNX embedding model plus Postgres plus the JVM need headroom).

### 2.4 Node.js LTS (via fnm, so versions can be pinned per project)
```bash
brew install fnm
```
```bash
echo 'eval "$(fnm env --use-on-cd --shell zsh)"' >> ~/.zshrc
```
```bash
fnm install --lts
```

### 2.5 CLI clients
```bash
brew install libpq redis awscli gh
```
```bash
brew link --force libpq
```
(Installing `redis` gives you `redis-cli`. Don't start the brew Redis service; Redis runs in Docker.)

### 2.6 Git identity
```bash
git config --global user.name "Your Name"
```
```bash
git config --global user.email "you@example.com"
```
```bash
gh auth login
```

### 2.7 AWS (only when you reach M8; the audit made no AWS calls)
```bash
aws configure sso
```
Prefer SSO or short-lived credentials over long-lived access keys. Before anything else, look at the **Billing console** to see whether the old RDS instance is still running and costing money.

## 3. Project bootstrap

Verified 2026-10-21 (M0-03/M0-04). The default config targets the local stack, so no profile flag is needed. If you don't have a `.env` yet, copy the example and fill in real values (generate secrets with `openssl rand -base64 48`):

```bash
cp .env.example .env
```

Start Postgres (pgvector) on `localhost:5433` and Redis on `localhost:6379`:
```bash
docker compose -f compose.dev.yaml up -d
```

Run the backend (it reads `.env` itself):
```bash
./mvnw spring-boot:run
```

Or run the backend in a container (docker profile) against the same dev Postgres:
```bash
docker compose up -d --build
```

Run the tests. They start their own throwaway Postgres and Redis containers through Testcontainers, so they only need Docker running and don't read `.env`:
```bash
./mvnw clean verify
```

Docker Desktop puts its CLI in `~/.docker/bin`. If `docker` isn't found in a new terminal, add it to your PATH:
```bash
echo 'export PATH="$HOME/.docker/bin:$PATH"' >> ~/.zprofile
```

## 4. Verification checklist

- [ ] `java -version` → 21.x (Temurin)
- [ ] `./mvnw -v` downloads Maven and reports Java 21
- [ ] `docker run --rm hello-world` succeeds
- [ ] `node -v` → current LTS, `npm -v` works
- [ ] `psql --version`, `redis-cli --version`, `aws --version`, `gh --version`
- [ ] `git config --global user.email` prints your address

## 5. Secrets hygiene on the new machine

- Keep real values only in an untracked `.env` (already gitignored) or a password manager. Never put them in `application*.properties`.
- Generate fresh secrets locally. Don't copy the old JWT secret from Git history (it's compromised):
```bash
openssl rand -base64 48
```
