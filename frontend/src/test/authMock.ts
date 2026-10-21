import { http, HttpResponse } from 'msw'

import type { ApiError } from '@/lib/api/client'
import { API_BASE_URL } from '@/lib/api/config'
import type { components } from '@/lib/api/schema'

type User = components['schemas']['UserResponse']

/**
 * A tiny in-memory imitation of the backend's /api/auth endpoints, so tests
 * exercise the real frontend auth code (token in memory, refresh cookie,
 * retry on 401) without a server.
 */

export const DEMO_USER: User = {
  id: 1,
  name: 'Ada Lovelace',
  email: 'ada@example.com',
  role: 'ROLE_USER',
}
export const DEMO_PASSWORD = 'Passw0rd!23'

type Account = { user: User; password: string }

const state = {
  accounts: new Map<string, Account>(),
  /** Whether the browser holds a valid refresh cookie. */
  hasSession: true,
  validAccessTokens: new Set<string>(),
  tokenCounter: 0,
  refreshCalls: 0,
}

export function resetAuthMock({ signedIn = true }: { signedIn?: boolean } = {}) {
  state.accounts = new Map([[DEMO_USER.email, { user: DEMO_USER, password: DEMO_PASSWORD }]])
  state.hasSession = signedIn
  state.validAccessTokens.clear()
  state.tokenCounter = 0
  state.refreshCalls = 0
}

/** Simulates the 15-minute access token running out. */
export function expireAccessTokens() {
  state.validAccessTokens.clear()
}

/** Simulates the session being revoked or expiring on the server. */
export function endServerSession() {
  state.hasSession = false
  state.validAccessTokens.clear()
}

export function refreshCallCount() {
  return state.refreshCalls
}

function error(status: number, message: string, path: string): HttpResponse<ApiError> {
  const reason = { 400: 'Bad Request', 401: 'Unauthorized', 409: 'Conflict' }[status] ?? 'Error'
  return HttpResponse.json(
    { timestamp: new Date().toISOString(), status, error: reason, message, path },
    { status },
  )
}

function signIn(user: User) {
  state.hasSession = true
  const accessToken = `access-${++state.tokenCounter}`
  state.validAccessTokens.add(accessToken)
  return HttpResponse.json({ accessToken, tokenType: 'Bearer', expiresIn: 900, user })
}

export const authHandlers = [
  http.post(`${API_BASE_URL}/api/auth/login`, async ({ request }) => {
    const { email, password } = (await request.json()) as { email: string; password: string }
    const account = state.accounts.get(email.trim().toLowerCase())
    if (!account || account.password !== password) {
      return error(401, 'Invalid email or password', '/api/auth/login')
    }
    return signIn(account.user)
  }),

  http.post(`${API_BASE_URL}/api/auth/register`, async ({ request }) => {
    const body = (await request.json()) as { name: string; email: string; password: string }
    const email = body.email.trim().toLowerCase()
    if (state.accounts.has(email)) {
      return error(409, 'An account with this email already exists', '/api/auth/register')
    }
    const user: User = { id: state.accounts.size + 1, name: body.name, email, role: 'ROLE_USER' }
    state.accounts.set(email, { user, password: body.password })
    return HttpResponse.json(user, { status: 201 })
  }),

  http.post(`${API_BASE_URL}/api/auth/refresh`, () => {
    state.refreshCalls++
    if (!state.hasSession) return error(401, 'Not signed in', '/api/auth/refresh')
    return signIn(DEMO_USER)
  }),

  http.post(`${API_BASE_URL}/api/auth/logout`, () => {
    state.hasSession = false
    state.validAccessTokens.clear()
    return new HttpResponse(null, { status: 204 })
  }),

  http.get(`${API_BASE_URL}/api/auth/me`, ({ request }) => {
    const token = request.headers.get('Authorization')?.replace('Bearer ', '')
    if (!token || !state.validAccessTokens.has(token)) {
      return error(401, 'Access token has expired', '/api/auth/me')
    }
    return HttpResponse.json(DEMO_USER)
  }),
]
