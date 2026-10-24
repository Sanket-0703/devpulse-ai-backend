/**
 * Messages for the `reason` the backend puts on /github/callback?result=error
 * (see GitHubConnectionServiceImpl).
 */
const REASONS: Record<string, string> = {
  access_denied: 'You cancelled on GitHub, so nothing was changed.',
  invalid_state:
    'That GitHub link has expired or was already used. Please start again from DevPulse.',
  email_exists:
    'A DevPulse account with your GitHub email already exists. Sign in with your password, then connect GitHub in Settings → GitHub.',
  no_verified_email:
    'Your GitHub account has no verified email address. Verify one on GitHub (Settings → Emails) and try again.',
  github_account_in_use: 'This GitHub account is already connected to another DevPulse account.',
  already_connected:
    'Your DevPulse account is connected to a different GitHub account. Disconnect it first.',
  not_connected:
    'GitHub sent you back, but this GitHub account isn’t connected to DevPulse yet. Connect it from Settings → GitHub.',
  github_reconnect: 'The GitHub connection has expired. Please connect GitHub again.',
  github_error: 'GitHub didn’t respond as expected. Please try again in a moment.',
}

export function githubErrorMessage(reason: string | null): string {
  return (reason && REASONS[reason]) ?? REASONS.github_error
}
