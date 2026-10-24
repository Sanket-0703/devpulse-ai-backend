import { zodResolver } from '@hookform/resolvers/zod'
import { CircleAlertIcon, Loader2Icon } from 'lucide-react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router'

import { FormField } from '@/components/common/FormField'
import { PasswordInput } from '@/components/common/PasswordInput'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { ApiRequestError, errorMessage } from '@/lib/api/errors'
import { applyServerFieldErrors } from '@/lib/forms'

import { GitHubSignInButton } from '@/features/github/GitHubSignInButton'

import { useAuth } from '../authContext'
import { type RegisterValues, registerSchema } from '../schemas'

const SERVER_FIELDS = ['name', 'email', 'password'] as const

export function RegisterPage() {
  const { register: registerAccount } = useAuth()
  const [formError, setFormError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<RegisterValues>({
    resolver: zodResolver(registerSchema),
    defaultValues: { name: '', email: '', password: '', confirmPassword: '' },
  })

  const onSubmit = handleSubmit(async ({ name, email, password }) => {
    setFormError(null)
    try {
      await registerAccount({ name, email, password })
    } catch (error) {
      if (error instanceof ApiRequestError && error.status === 409) {
        setError('email', { message: error.message }, { shouldFocus: true })
        return
      }
      if (applyServerFieldErrors(error, setError, SERVER_FIELDS)) return
      setFormError(errorMessage(error))
    }
  })

  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h1 className="text-xl">Create your account</h1>
        </CardTitle>
        <CardDescription>AI code review for your GitHub pull requests.</CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={onSubmit} noValidate className="grid gap-4">
          <GitHubSignInButton />

          {formError && (
            <Alert variant="destructive">
              <CircleAlertIcon />
              <AlertDescription>{formError}</AlertDescription>
            </Alert>
          )}

          <FormField label="Name" error={errors.name?.message}>
            <Input autoComplete="name" autoFocus {...register('name')} />
          </FormField>

          <FormField label="Email" error={errors.email?.message}>
            <Input type="email" autoComplete="email" {...register('email')} />
          </FormField>

          <FormField label="Password" error={errors.password?.message} hint="8 to 72 characters">
            <PasswordInput autoComplete="new-password" {...register('password')} />
          </FormField>

          <FormField label="Confirm password" error={errors.confirmPassword?.message}>
            <PasswordInput autoComplete="new-password" {...register('confirmPassword')} />
          </FormField>

          <Button type="submit" disabled={isSubmitting}>
            {isSubmitting && <Loader2Icon className="animate-spin" />}
            Create account
          </Button>

          <p className="text-muted-foreground text-center text-sm">
            Already have an account?{' '}
            <Link
              to="/login"
              className="text-foreground font-medium underline-offset-4 hover:underline"
            >
              Sign in
            </Link>
          </p>
        </form>
      </CardContent>
    </Card>
  )
}
