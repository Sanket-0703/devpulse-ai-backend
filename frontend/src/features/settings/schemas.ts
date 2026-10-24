import { z } from 'zod'

// Mirrors ProfileUpdateRequest.java / ChangePasswordRequest.java.

/** Same normalization as the backend (EmailAddresses.normalize). */
export function normalizeEmail(email: string): string {
  return email.trim().toLowerCase()
}

/**
 * Built per user because one rule depends on who you are: the current
 * password is required only if the email differs from `currentEmail`.
 */
export function profileSchema(currentEmail: string) {
  return z
    .object({
      name: z
        .string()
        .trim()
        .min(1, 'Name is required')
        .max(100, 'Name must be at most 100 characters'),
      email: z
        .string()
        .trim()
        .min(1, 'Email is required')
        .max(255, 'Email must be at most 255 characters')
        .pipe(z.email('Enter a valid email')),
      currentPassword: z.string(),
    })
    .refine(
      (values) => normalizeEmail(values.email) === currentEmail || values.currentPassword !== '',
      { path: ['currentPassword'], message: 'Enter your current password to change your email' },
    )
}

export const changePasswordSchema = z
  .object({
    currentPassword: z.string().min(1, 'Current password is required'),
    newPassword: z
      .string()
      .min(8, 'Password must be at least 8 characters')
      .max(72, 'Password must be at most 72 characters'),
    confirmPassword: z.string(),
  })
  .refine((values) => values.newPassword === values.confirmPassword, {
    path: ['confirmPassword'],
    message: "Passwords don't match",
  })
  .refine((values) => values.newPassword !== values.currentPassword, {
    path: ['newPassword'],
    message: 'New password must be different from the current one',
  })

export type ProfileValues = z.infer<ReturnType<typeof profileSchema>>
export type ChangePasswordValues = z.infer<typeof changePasswordSchema>
