import { api, API_BASE_URL } from '@/lib/api/client'
import { unwrap } from '@/lib/api/errors'
import type { components } from '@/lib/api/schema'

export type GitHubConnection = components['schemas']['GitHubConnectionResponse']

/**
 * "Sign in with GitHub" is a plain link the browser follows (not fetch):
 * the backend redirects to GitHub, GitHub back to the backend, the backend
 * to /github/callback here.
 */
export const GITHUB_SIGN_IN_URL = `${API_BASE_URL}/api/auth/github`

export const githubConnectionKey = ['github-connection'] as const

export async function fetchConnection(): Promise<GitHubConnection> {
  return unwrap(await api.GET('/api/github/connection'))
}

/** GitHub URL that links the result to the signed-in user (valid 10 minutes). */
export async function startConnect(): Promise<string> {
  return unwrap(await api.POST('/api/github/connect')).url
}

/** GitHub's "install DevPulse on which repositories?" page. */
export async function startInstall(): Promise<string> {
  return unwrap(await api.POST('/api/github/install')).url
}

export async function disconnect(): Promise<void> {
  unwrap(await api.DELETE('/api/github/connection'))
}
