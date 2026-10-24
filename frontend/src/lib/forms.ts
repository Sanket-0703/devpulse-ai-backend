import type { FieldValues, Path, UseFormSetError } from 'react-hook-form'

import { ApiRequestError } from './api/errors'

/**
 * Shows the backend's per-field messages (a 400 ErrorResponse with
 * `errors[]`) next to the matching inputs. Returns true if at least one was
 * applied, so the caller can skip its generic error message.
 */
export function applyServerFieldErrors<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
  fields: readonly Path<T>[],
): boolean {
  if (!(error instanceof ApiRequestError) || error.status !== 400) return false

  const fieldErrors = error.fieldErrors
  let applied = false
  fields.forEach((field, index) => {
    const message = fieldErrors[field]
    if (message) {
      // Focus the first field with an error.
      setError(field, { message }, { shouldFocus: index === 0 || !applied })
      applied = true
    }
  })
  return applied
}
