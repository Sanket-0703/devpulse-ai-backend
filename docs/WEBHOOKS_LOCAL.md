# Receiving GitHub webhooks on your Mac

GitHub sends webhooks over the internet, and your Mac's `localhost:8080` isn't reachable from the internet. A **tunnel** fixes that:
- GitHub posts to a public URL;
- a small program on your Mac picks each delivery up and forwards it to the backend.

```
GitHub ──POST──► https://smee.io/<your channel>  ◄──(long-lived connection)── smee client on your Mac
                                                                                  │
                                                                                  ▼
                                                    http://localhost:8080/api/github/webhooks
```

This guide uses **smee.io**, made by GitHub's Probot team for exactly this: free, no account, nothing to install permanently. ngrok works too (see the end).

> The tunnel URL only forwards. DevPulse still checks every delivery's **signature** with your webhook secret, so a stranger who finds the URL can't make DevPulse do anything.

---

## 1. Create a webhook secret

```bash
openssl rand -hex 32
```

Put it in the backend's `.env` (never in Git, never in chat):

```properties
GITHUB_WEBHOOK_SECRET=<the value you just generated>
```

Restart the backend. **Settings → Reviews** in DevPulse no longer says "Webhooks aren't set up on this server".

## 2. Get a smee.io channel

Open <https://smee.io> and click **Start a new channel**. Copy the URL (`https://smee.io/AbCdEf123…`) and keep the page open: it shows each delivery as it arrives, which is handy for debugging.

## 3. Run the smee client

In a terminal (keep it running while you work):

```bash
npx smee-client --url https://smee.io/<your channel> --target http://localhost:8080/api/github/webhooks
```

`npx` downloads the client on first use (it asks before installing).

## 4. Point the GitHub App at it

GitHub → **Settings → Developer settings → GitHub Apps → your DevPulse app → General**:

| Field | Value |
|---|---|
| Webhook → **Active** | ☑ checked |
| Webhook URL | your smee.io channel URL |
| Webhook secret | the same value as `GITHUB_WEBHOOK_SECRET` |

Save. Then **Permissions & events**:
- **Repository permissions → Pull requests:** *Read and write* (already set since Level 4; DevPulse needs *write* to post review comments).
- **Subscribe to events:** tick **Pull request**.
- Installation events (app installed, uninstalled, repositories added) are always sent, with no box to tick.

Save. If GitHub asks to **accept new permissions** on your installation (an e-mail, or a banner on the installation page), accept them.

## 5. Check it works

1. In the app's settings → **Advanced → Recent Deliveries** there is a `ping`. It should say **200** with the body `pong`. Press **Redeliver** to try again at any time: DevPulse answers `duplicate delivery, ignored`, which is the duplicate protection working.
2. In DevPulse:
   - turn on **AI review** for a test repository;
   - **Sync** its pull requests once, so DevPulse knows the repository's pull requests.
3. Push a commit to an open pull request in that repository:
   - **Settings → Reviews → GitHub webhooks** shows `pull_request.synchronize`: *processed*, "Review started.";
   - the pull request's page shows a new review marked **Automatic**.
4. Press **Publish to GitHub** on the review (or turn on *Publish automatic reviews* first). The comments appear on the pull request on GitHub.

## Troubleshooting

| You see | Why | Fix |
|---|---|---|
| GitHub: **503** | `GITHUB_WEBHOOK_SECRET` isn't set (or the backend wasn't restarted) | set it, restart |
| GitHub: **401** | the secrets don't match | paste the same value in `.env` and the app's settings |
| GitHub: timeout / nothing on smee | the smee client isn't running | restart the `npx smee-client` command |
| DevPulse: *ignored*, "a repository DevPulse doesn't follow" | the repository isn't synced in DevPulse | Repositories → Sync now |
| DevPulse: "AI review is off for this repository" | the repository's switch is off | turn it on |
| Publishing: "GitHub refused the request" | new commits since the review, or no *Pull requests: write* | review again; check the permission |

## Using ngrok instead

```bash
ngrok http 8080
```

Use `https://<random>.ngrok-free.app/api/github/webhooks` as the webhook URL. Unlike smee, ngrok exposes your whole backend on that public URL while it runs, and the URL changes every time you restart it (on the free plan).
