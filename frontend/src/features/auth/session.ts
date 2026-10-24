import { API_BASE_URL } from '@/lib/api/config'
import type { components } from '@/lib/api/schema'

export type AuthResponse = components['schemas']['AuthResponse']

/**
 * The access token lives only in this module's memory, never in
 * localStorage, so injected scripts can't read it from storage. A page
 * reload loses it; `refreshSession()` gets a new one using the httpOnly
 * refresh-token cookie that the backend set at login.
 */
let accessToken: string | null = null

export function getAccessToken(): string | null {
  return accessToken
}

export function setAccessToken(token: string | null): void {
  accessToken = token
}

// ---------- session-ended notifications ----------

type Listener = () => void
const sessionEndedListeners = new Set<Listener>()

/** Called when a refresh fails mid-session (the session expired or was revoked). */
export function onSessionEnded(listener: Listener): () => void {
  sessionEndedListeners.add(listener)
  return () => sessionEndedListeners.delete(listener)
}

export function notifySessionEnded(): void {
  accessToken = null
  sessionEndedListeners.forEach((listener) => listener())
}

// ---------- refresh ----------

let refreshInFlight: Promise<AuthResponse | null> | null = null

/**
 * Trades the refresh-token cookie for a new access token. Returns null when
 * there is no valid session.
 *
 * Calls are de-duplicated: everything that needs a refresh at the same time
 * (React StrictMode's double effects, several 401s at once) shares one
 * request. The backend rotates the refresh token on every use, so two
 * parallel refreshes with the same cookie would look like token theft.
 */
export function refreshSession(): Promise<AuthResponse | null> {
  refreshInFlight ??= withCrossTabLock(requestRefresh).finally(() => {
    refreshInFlight = null
  })
  return refreshInFlight
}

/**
 * Other tabs share the same cookie, so refreshes are also serialized across
 * tabs with the Web Locks API: the second tab waits, then refreshes with the
 * cookie the first tab just received.
 */
function withCrossTabLock<T>(task: () => Promise<T>): Promise<T> {
  if (typeof navigator !== 'undefined' && navigator.locks) {
    // The DOM typings return Promise<Promise<T>> for an async callback; .then unwraps it.
    return navigator.locks.request('devpulse-auth-refresh', task).then((result) => result)
  }
  return task()
}

async function requestRefresh(): Promise<AuthResponse | null> {
  try {
    const response = await fetch(`${API_BASE_URL}/api/auth/refresh`, {
      method: 'POST',
      credentials: 'include',
    })
    if (!response.ok) {
      accessToken = null
      return null
    }
    const body = (await response.json()) as AuthResponse
    accessToken = body.accessToken
    return body
  } catch {
    // Backend unreachable: treat as signed out rather than crashing the app.
    accessToken = null
    return null
  }
}
