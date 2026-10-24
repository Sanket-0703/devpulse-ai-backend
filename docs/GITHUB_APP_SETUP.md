# Creating the DevPulse GitHub App (local development)

DevPulse talks to GitHub as a **GitHub App**. You register it once, in your own GitHub account; it takes about 5 minutes. Nothing here costs money.

> Why a GitHub App and not an OAuth App: fine-grained permissions, you choose which repositories it can see, short-lived tokens, one built-in webhook, and review comments posted as a bot. See [LEVEL_4.md](levels/LEVEL_4.md).

## 1. Open the registration page

https://github.com/settings/apps/new (GitHub → your avatar → **Settings** → **Developer settings** → **GitHub Apps** → **New GitHub App**).

## 2. Fill in the form

| Section | Field | Value |
|---|---|---|
| Basics | **GitHub App name** | `DevPulse AI Dev <yourname>` — must be unique on all of GitHub, e.g. `DevPulse AI Dev Sanket`. Its URL slug (e.g. `devpulse-ai-dev-sanket`) becomes the bot name `devpulse-ai-dev-sanket[bot]`. |
| | **Description** | `AI code review, vulnerability detection and semantic code search (local development)` |
| | **Homepage URL** | `https://github.com/Sanket-0703/devpulse-ai-backend` |
| Identifying and authorizing users | **Callback URL** | `http://localhost:8080/api/github/oauth/callback` |
| | **Expire user authorization tokens** | ✅ checked (user tokens last 8 hours and come with a refresh token) |
| | **Request user authorization (OAuth) during installation** | ✅ checked (installing the app also signs you in with GitHub, in one step) |
| | **Enable Device Flow** | ☐ unchecked |
| Post installation | **Setup URL** | leave empty (greyed out because of the option above) |
| | **Redirect on update** | ✅ checked (after you change which repos the app can see, GitHub sends you back to DevPulse) |
| Webhook | **Active** | ☐ **unchecked** for now. Level 9 turns it on. |

### Permissions

Only what DevPulse needs. Everything not listed stays **No access**.

| Type | Permission | Access | Used for |
|---|---|---|---|
| Repository | **Contents** | Read-only | Reading code to review and index (Levels 6–10) |
| Repository | **Metadata** | Read-only | Mandatory; repo names and basic info |
| Repository | **Pull requests** | Read and write | Reading PRs (Level 6), posting review comments (Level 9) |
| Account | **Email addresses** | Read-only | "Sign in with GitHub": find your verified email to link or create your DevPulse account |

**Subscribe to events:** none for now (the webhook is off).

**Where can this GitHub App be installed?** → **Only on this account**.

Click **Create GitHub App**.

## 3. Collect the credentials

You land on the app's **General** page (left sidebar: General · Permissions & events · **Credentials** · Optional features · Advanced · Install App).

1. On **General**, note the **App ID** (a number) and the **Client ID** (starts with `Iv`).
2. Open **Credentials** in the left sidebar:
   - **Client secrets** tab → **Generate a new client secret**. Copy it right away; GitHub shows it only once.
   - **Key pairs** tab → **New key**. A `.pem` file downloads (GitHub keeps no copy you can download again). DevPulse uses it from Level 5 on (to act as the app on installed repositories), but generate it now so setup is done in one go.
3. Click **Public page** at the bottom of the left sidebar. Its URL is `https://github.com/apps/<slug>` (e.g. `devpulse-ai-dev-sanket`); the last part is the **slug**.

## 4. Put them in `.env` (never in chat, never in Git)

Move the private key out of Downloads into a private folder:

```bash
mkdir -p ~/.devpulse && mv ~/Downloads/devpulse-ai-dev-*.private-key.pem ~/.devpulse/github-app.pem && chmod 600 ~/.devpulse/github-app.pem
```

Then edit `.env` in the repo root (in IntelliJ or VS Code) and set:

```properties
GITHUB_APP_ID=123456
GITHUB_APP_SLUG=devpulse-ai-dev-sanket
GITHUB_CLIENT_ID=Iv23li...
GITHUB_CLIENT_SECRET=...the secret from step 3.2...
GITHUB_PRIVATE_KEY_PATH=/Users/<you>/.devpulse/github-app.pem
GITHUB_REDIRECT_URI=http://localhost:8080/api/github/oauth/callback
```

`.env` is gitignored, and so are `*.pem` files.

## 5. Restart the backend

The backend reads `.env` at startup. Stop it and start it again (IntelliJ's **Backend** configuration, or `./mvnw spring-boot:run`).

## Later

- **Change which repositories DevPulse sees:** DevPulse → Settings → GitHub → *Manage repositories*, or GitHub → Settings → Applications → Installed GitHub Apps.
- **Lost the client secret:** generate a new one on the app's page and update `.env`.
- **Leaked a secret or the private key:** delete it on the app's page right away, generate a new one, update `.env`.
