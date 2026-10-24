import { api } from '@/lib/api/client'
import { unwrap } from '@/lib/api/errors'
import type { components } from '@/lib/api/schema'

import { type AuthResponse, setAccessToken } from './session'

export type User = components['schemas']['UserResponse']
export type LoginBody = components['schemas']['LoginRequest']
export type RegisterBody = components['schemas']['RegisterRequest']

export async function login(body: LoginBody): Promise<AuthResponse> {
  const data = unwrap(await api.POST('/api/auth/login', { body }))
  setAccessToken(data.accessToken)
  return data
}

export async function register(body: RegisterBody): Promise<User> {
  return unwrap(await api.POST('/api/auth/register', { body }))
}

/**
 * Sends the access token (added by authFetch) and the refresh cookie so the
 * backend can revoke both. Never throws: signing out locally must work even
 * if the backend is down.
 */
export async function logout(): Promise<void> {
  try {
    await api.POST('/api/auth/logout')
  } catch {
    // Ignore: the cookie expires on its own and the token is dropped below.
  } finally {
    setAccessToken(null)
  }
}

/**
 * Ends every session on every device; the server also clears this device's
 * cookie. Throws if the request fails, and then nothing changes locally:
 * the user must not think they signed out everywhere when they didn't.
 */
export async function logoutEverywhere(): Promise<void> {
  unwrap(await api.POST('/api/auth/logout-all'))
  setAccessToken(null)
}

export async function fetchCurrentUser(): Promise<User> {
  return unwrap(await api.GET('/api/auth/me'))
}
