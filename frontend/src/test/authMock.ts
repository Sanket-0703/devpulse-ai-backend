import { http, HttpResponse } from 'msw'

import type { ApiError } from '@/lib/api/client'
import { API_BASE_URL } from '@/lib/api/config'
import type { components } from '@/lib/api/schema'

type User = components['schemas']['UserResponse']

/**
 * A tiny in-memory imitation of the backend's account endpoints
 * (/api/auth/*, /api/profile*, /api/github/connection…), so tests exercise the real frontend code
 * (token in memory, refresh cookie, retry on 401, settings forms) without a
 * server. It follows the backend's rules and messages.
 */

export const DEMO_USER: User = {
  id: 1,
  name: 'Ada Lovelace',
  email: 'ada@example.com',
  role: 'ROLE_USER',
  createdAt: '2026-10-01T09:30:00Z',
  hasPassword: true,
}
export const DEMO_PASSWORD = 'Passw0rd!23'

/** A second account, for "email already taken". */
export const OTHER_USER: User = {
  id: 2,
  name: 'Grace Hopper',
  email: 'grace@example.com',
  role: 'ROLE_USER',
  createdAt: '2026-10-02T09:30:00Z',
  hasPassword: true,
}

/** password null = account created with "Sign in with GitHub" and no password set yet. */
type Account = { user: User; password: string | null; github?: GitHubLink }

type GitHubLink = { username: string; installed: boolean }

export const GITHUB_AUTHORIZE_URL = 'https://github.com/login/oauth/authorize?state=test-state'
export const GITHUB_INSTALL_URL =
  'https://github.com/apps/devpulse-test/installations/new?state=test-state'

const state = {
  accounts: new Map<number, Account>(),
  /** Id of the account whose refresh cookie the browser holds; null = none. */
  sessionUserId: null as number | null,
  /** access token → user id */
  accessTokens: new Map<string, number>(),
  tokenCounter: 0,
  refreshCalls: 0,
}

export function resetAuthMock({
  signedIn = true,
  githubOnly = false,
}: {
  signedIn?: boolean
  /** The demo user signed up with GitHub: no password, GitHub connected. */
  githubOnly?: boolean
} = {}) {
  state.accounts = new Map<number, Account>([
    [
      DEMO_USER.id,
      githubOnly
        ? {
            user: { ...DEMO_USER, hasPassword: false },
            password: null,
            github: { username: 'octo-ada', installed: false },
          }
        : { user: { ...DEMO_USER }, password: DEMO_PASSWORD },
    ],
    [OTHER_USER.id, { user: { ...OTHER_USER }, password: DEMO_PASSWORD }],
  ])
  state.sessionUserId = signedIn ? DEMO_USER.id : null
  state.accessTokens.clear()
  state.tokenCounter = 0
  state.refreshCalls = 0
}

/** Simulates the 15-minute access token running out. */
export function expireAccessTokens() {
  state.accessTokens.clear()
}

/** Simulates the session being revoked or expiring on the server. */
export function endServerSession() {
  state.sessionUserId = null
  state.accessTokens.clear()
}

export function refreshCallCount() {
  return state.refreshCalls
}

/** Connects (or with null, disconnects) GitHub for the demo user. */
export function setDemoGitHub(link: GitHubLink | null) {
  const account = state.accounts.get(DEMO_USER.id)
  if (account) account.github = link ?? undefined
}

/** The stored password of the demo account (to check a password change). */
export function demoAccountPassword() {
  return state.accounts.get(DEMO_USER.id)?.password
}

function error(
  status: number,
  message: string,
  path: string,
  errors?: ApiError['errors'],
): HttpResponse<ApiError> {
  const reason =
    { 400: 'Bad Request', 401: 'Unauthorized', 409: 'Conflict' }[status as 400 | 401 | 409] ??
    'Error'
  return HttpResponse.json(
    { timestamp: new Date().toISOString(), status, error: reason, message, path, errors },
    { status },
  )
}

function fieldError(field: string, message: string, path: string) {
  return error(400, message, path, [{ field, message }])
}

function findByEmail(email: string): Account | undefined {
  const normalized = email.trim().toLowerCase()
  return [...state.accounts.values()].find((account) => account.user.email === normalized)
}

function signIn(account: Account) {
  state.sessionUserId = account.user.id
  const accessToken = `access-${++state.tokenCounter}`
  state.accessTokens.set(accessToken, account.user.id)
  return HttpResponse.json({ accessToken, tokenType: 'Bearer', expiresIn: 900, user: account.user })
}

/** The account behind the request's Bearer token, or undefined (→ 401). */
function authenticated(request: Request): Account | undefined {
  const token = request.headers.get('Authorization')?.replace('Bearer ', '') ?? ''
  const userId = state.accessTokens.get(token)
  return userId === undefined ? undefined : state.accounts.get(userId)
}

const expired = (path: string) => error(401, 'Access token has expired', path)

export const authHandlers = [
  http.post(`${API_BASE_URL}/api/auth/login`, async ({ request }) => {
    const { email, password } = (await request.json()) as { email: string; password: string }
    const account = findByEmail(email)
    if (!account || account.password === null || account.password !== password) {
      return error(401, 'Invalid email or password', '/api/auth/login')
    }
    return signIn(account)
  }),

  http.post(`${API_BASE_URL}/api/auth/register`, async ({ request }) => {
    const body = (await request.json()) as { name: string; email: string; password: string }
    if (findByEmail(body.email)) {
      return error(409, 'An account with this email already exists', '/api/auth/register')
    }
    const user: User = {
      id: state.accounts.size + 1,
      name: body.name,
      email: body.email.trim().toLowerCase(),
      role: 'ROLE_USER',
      createdAt: new Date().toISOString(),
      hasPassword: true,
    }
    state.accounts.set(user.id, { user, password: body.password })
    return HttpResponse.json(user, { status: 201 })
  }),

  http.post(`${API_BASE_URL}/api/auth/refresh`, () => {
    state.refreshCalls++
    const account =
      state.sessionUserId === null ? undefined : state.accounts.get(state.sessionUserId)
    if (!account) return error(401, 'Not signed in', '/api/auth/refresh')
    return signIn(account)
  }),

  http.post(`${API_BASE_URL}/api/auth/logout`, () => {
    state.sessionUserId = null
    state.accessTokens.clear()
    return new HttpResponse(null, { status: 204 })
  }),

  http.post(`${API_BASE_URL}/api/auth/logout-all`, ({ request }) => {
    if (!authenticated(request)) return expired('/api/auth/logout-all')
    endServerSession()
    return new HttpResponse(null, { status: 204 })
  }),

  http.get(`${API_BASE_URL}/api/auth/me`, ({ request }) => {
    const account = authenticated(request)
    return account ? HttpResponse.json(account.user) : expired('/api/auth/me')
  }),

  http.put(`${API_BASE_URL}/api/profile`, async ({ request }) => {
    const account = authenticated(request)
    if (!account) return expired('/api/profile')
    const body = (await request.json()) as { name: string; email: string; currentPassword?: string }
    const email = body.email.trim().toLowerCase()

    if (email !== account.user.email) {
      if (account.password === null) {
        return fieldError(
          'currentPassword',
          'Set a password in Settings → Security before changing your email',
          '/api/profile',
        )
      }
      if (!body.currentPassword) {
        return fieldError(
          'currentPassword',
          'Enter your current password to change your email',
          '/api/profile',
        )
      }
      if (body.currentPassword !== account.password) {
        return fieldError('currentPassword', 'Current password is incorrect', '/api/profile')
      }
      if (findByEmail(email)) {
        return error(409, 'An account with this email already exists', '/api/profile')
      }
    }
    account.user = { ...account.user, name: body.name.trim(), email }
    return HttpResponse.json(account.user)
  }),

  http.put(`${API_BASE_URL}/api/profile/password`, async ({ request }) => {
    const account = authenticated(request)
    if (!account) return expired('/api/profile/password')
    const body = (await request.json()) as { currentPassword?: string; newPassword: string }

    // A GitHub-only account sets its first password without a current one.
    if (account.password !== null && body.currentPassword !== account.password) {
      return fieldError('currentPassword', 'Current password is incorrect', '/api/profile/password')
    }
    account.password = body.newPassword
    account.user = { ...account.user, hasPassword: true }
    state.accessTokens.clear() // every other session ends…
    return signIn(account) // …and this device gets a fresh one
  }),

  http.get(`${API_BASE_URL}/api/github/connection`, ({ request }) => {
    const account = authenticated(request)
    if (!account) return expired('/api/github/connection')
    const github = account.github
    return HttpResponse.json(
      github
        ? {
            connected: true,
            username: github.username,
            avatarUrl: null,
            profileUrl: `https://github.com/${github.username}`,
            connectedAt: '2026-10-20T08:00:00Z',
            installed: github.installed,
            manageRepositoriesUrl: github.installed
              ? 'https://github.com/settings/installations/555'
              : null,
          }
        : { connected: false, installed: false },
    )
  }),

  http.post(`${API_BASE_URL}/api/github/connect`, ({ request }) =>
    authenticated(request)
      ? HttpResponse.json({ url: GITHUB_AUTHORIZE_URL })
      : expired('/api/github/connect'),
  ),

  http.post(`${API_BASE_URL}/api/github/install`, ({ request }) =>
    authenticated(request)
      ? HttpResponse.json({ url: GITHUB_INSTALL_URL })
      : expired('/api/github/install'),
  ),

  http.delete(`${API_BASE_URL}/api/github/connection`, ({ request }) => {
    const account = authenticated(request)
    if (!account) return expired('/api/github/connection')
    if (account.password === null) {
      return error(
        409,
        'Set a password in Settings → Security before disconnecting GitHub, otherwise you won’t be able to sign in.',
        '/api/github/connection',
      )
    }
    account.github = undefined
    return new HttpResponse(null, { status: 204 })
  }),
]
