import type { ApiError } from './client'

/** A non-2xx response from the backend, with its parsed ErrorResponse body. */
export class ApiRequestError extends Error {
  readonly status: number
  readonly body: ApiError | undefined

  constructor(status: number, body: ApiError | undefined) {
    super(body?.message ?? `Request failed with status ${status}`)
    this.name = 'ApiRequestError'
    this.status = status
    this.body = body
  }

  /** Per-field validation messages from a 400, keyed by field name. */
  get fieldErrors(): Record<string, string> {
    return Object.fromEntries((this.body?.errors ?? []).map((e) => [e.field, e.message]))
  }
}

/** A user-facing message for anything thrown by an API call. */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiRequestError) return error.message
  if (error instanceof TypeError) return "Can't reach the server. Is the backend running?"
  return 'Something went wrong. Please try again.'
}

/**
 * Unwraps an openapi-fetch result: returns the data, or throws
 * ApiRequestError so callers (and TanStack Query) can handle failures in one
 * place.
 */
export function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.error !== undefined || !result.response.ok) {
    throw new ApiRequestError(result.response.status, result.error as ApiError | undefined)
  }
  return result.data as T
}
