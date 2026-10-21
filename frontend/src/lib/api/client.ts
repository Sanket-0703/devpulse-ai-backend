import createClient from 'openapi-fetch'

import type { components, paths } from './schema'

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

/**
 * Typed client for the Spring Boot API. Paths, request bodies and responses
 * are checked against src/lib/api/schema.d.ts, which is generated from the
 * backend's OpenAPI docs (`npm run api:types`).
 *
 * @example
 * const { data, error } = await api.POST('/api/auth/login', { body: { email, password } })
 */
export const api = createClient<paths>({ baseUrl: API_BASE_URL })

/** The error shape every backend endpoint returns (see ErrorResponse.java). */
export type ApiError = components['schemas']['ErrorResponse']
