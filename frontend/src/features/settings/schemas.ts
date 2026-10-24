import { z } from 'zod'

// Mirrors ProfileUpdateRequest.java / ChangePasswordRequest.java.

/** Same normalization as the backend (EmailAddresses.normalize). */
export function normalizeEmail(email: string): string {
  return email.trim().toLowerCase()
}

/**
 * Built per user because the rules depend on who you are: changing the
 * email needs the current password, and accounts created with "Sign in with
 * GitHub" have no password yet, so they must set one first.
 */
export function profileSchema(currentEmail: string, hasPassword: boolean) {
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
    .refine((values) => hasPassword || normalizeEmail(values.email) === currentEmail, {
      path: ['email'],
      message: 'Set a password in the Security tab before changing your email',
    })
    .refine(
      (values) =>
        !hasPassword ||
        normalizeEmail(values.email) === currentEmail ||
        values.currentPassword !== '',
      { path: ['currentPassword'], message: 'Enter your current password to change your email' },
    )
}

const newPasswordRules = z
  .string()
  .min(8, 'Password must be at least 8 characters')
  .max(72, 'Password must be at most 72 characters')

/**
 * With a password: current + new + confirm, and new must differ.
 * Without one (GitHub-only account): just new + confirm.
 */
export function changePasswordSchema(hasPassword: boolean) {
  return z
    .object({
      currentPassword: hasPassword ? z.string().min(1, 'Current password is required') : z.string(),
      newPassword: newPasswordRules,
      confirmPassword: z.string(),
    })
    .refine((values) => values.newPassword === values.confirmPassword, {
      path: ['confirmPassword'],
      message: "Passwords don't match",
    })
    .refine((values) => !hasPassword || values.newPassword !== values.currentPassword, {
      path: ['newPassword'],
      message: 'New password must be different from the current one',
    })
}

export type ProfileValues = z.infer<ReturnType<typeof profileSchema>>
export type ChangePasswordValues = z.infer<ReturnType<typeof changePasswordSchema>>
