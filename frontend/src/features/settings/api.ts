import type { User } from '@/features/auth/api'
import { setAccessToken } from '@/features/auth/session'
import { api } from '@/lib/api/client'
import { unwrap } from '@/lib/api/errors'
import type { components } from '@/lib/api/schema'

export type ProfileUpdateBody = components['schemas']['ProfileUpdateRequest']
export type ChangePasswordBody = components['schemas']['ChangePasswordRequest']

export async function updateProfile(body: ProfileUpdateBody): Promise<User> {
  return unwrap(await api.PUT('/api/profile', { body }))
}

/**
 * The backend signs out every other device and starts a new session for
 * this one: a new access token in the body and a new refresh cookie.
 */
export async function changePassword(body: ChangePasswordBody): Promise<void> {
  const session = unwrap(await api.PUT('/api/profile/password', { body }))
  setAccessToken(session.accessToken)
}
