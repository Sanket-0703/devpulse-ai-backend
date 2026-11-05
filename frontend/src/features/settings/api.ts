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
export async function changePassword(body: ChangePasswordBody): Promise<User> {
  const session = unwrap(await api.PUT('/api/profile/password', { body }))
  setAccessToken(session.accessToken)
  return session.user
}

// ---------- review settings and webhooks (Level 9) ----------

export type ReviewSettings = components['schemas']['ReviewSettingsResponse']
export type ReviewSettingsBody = components['schemas']['ReviewSettingsRequest']
export type WebhookDelivery = components['schemas']['WebhookDeliveryResponse']

export const reviewSettingsKey = ['settings', 'reviews'] as const
export const webhookDeliveriesKey = ['settings', 'webhook-deliveries'] as const

export async function getReviewSettings(): Promise<ReviewSettings> {
  return unwrap(await api.GET('/api/settings/reviews'))
}

export async function saveReviewSettings(body: ReviewSettingsBody): Promise<ReviewSettings> {
  return unwrap(await api.PUT('/api/settings/reviews', { body }))
}

export async function listWebhookDeliveries(): Promise<WebhookDelivery[]> {
  return unwrap(await api.GET('/api/github/webhooks/deliveries'))
}
