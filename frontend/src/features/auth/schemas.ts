import { z } from 'zod'

// Mirrors the backend rules in RegisterRequest.java / LoginRequest.java, so
// most mistakes are caught before a request is sent. The backend still
// validates everything.

export const loginSchema = z.object({
  email: z.string().trim().min(1, 'Email is required').pipe(z.email('Enter a valid email')),
  password: z.string().min(1, 'Password is required'),
})

export const registerSchema = z
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
    password: z
      .string()
      .min(8, 'Password must be at least 8 characters')
      // bcrypt only uses the first 72 bytes of a password.
      .max(72, 'Password must be at most 72 characters'),
    confirmPassword: z.string(),
  })
  .refine((values) => values.password === values.confirmPassword, {
    path: ['confirmPassword'],
    message: "Passwords don't match",
  })

export type LoginValues = z.infer<typeof loginSchema>
export type RegisterValues = z.infer<typeof registerSchema>
