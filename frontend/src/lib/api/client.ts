import createClient from 'openapi-fetch'

import {
  getAccessToken,
  notifySessionEnded,
  refreshSession,
  setAccessToken,
} from '@/features/auth/session'

import { API_BASE_URL } from './config'
import type { components, paths } from './schema'

export { API_BASE_URL }

/** The error shape every backend endpoint returns (see ErrorResponse.java). */
export type ApiError = components['schemas']['ErrorResponse']

// Auth endpoints handle their own 401s (wrong password, no session); never
// retry them through a refresh.
const AUTH_PATHS = [
  '/api/auth/login',
  '/api/auth/register',
  '/api/auth/refresh',
  '/api/auth/logout',
]

function isAuthEndpoint(url: string): boolean {
  const { pathname } = new URL(url)
  return AUTH_PATHS.includes(pathname)
}

function withAccessToken(request: Request): Request {
  const token = getAccessToken()
  if (token) request.headers.set('Authorization', `Bearer ${token}`)
  return request
}

/**
 * fetch() for the typed client: adds the access token, and when the backend
 * answers 401 because it expired, refreshes once and retries the request.
 * If the refresh fails too, the session is over and the app is told so.
 */
export async function authFetch(request: Request): Promise<Response> {
  const retry = request.clone() // a Request body can only be read once
  const hadToken = getAccessToken() !== null

  const response = await globalThis.fetch(withAccessToken(request))

  if (response.status !== 401 || !hadToken || isAuthEndpoint(request.url)) {
    return response
  }

  setAccessToken(null)
  const refreshed = await refreshSession()

  if (!refreshed) {
    notifySessionEnded()
    return response
  }

  return globalThis.fetch(withAccessToken(retry))
}

/**
 * Typed client for the Spring Boot API. Paths, request bodies and responses
 * are checked against src/lib/api/schema.d.ts, which is generated from the
 * backend's OpenAPI docs (`npm run api:types`).
 *
 * @example
 * const { data, error } = await api.GET('/api/auth/me')
 */
export const api = createClient<paths>({
  baseUrl: API_BASE_URL,
  fetch: authFetch,
  // Send the refresh-token cookie; the browser only attaches it to /api/auth/*.
  credentials: 'include',
})
