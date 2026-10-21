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
import { errorMessage } from '@/lib/api/errors'

import { useAuth } from '../authContext'
import { type LoginValues, loginSchema } from '../schemas'

export function LoginPage() {
  const { login } = useAuth()
  const [formError, setFormError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: '', password: '' },
  })

  // On success, <GuestOnly> sees the new session and redirects into the app.
  const onSubmit = handleSubmit(async (values) => {
    setFormError(null)
    try {
      await login(values)
    } catch (error) {
      setFormError(errorMessage(error))
    }
  })

  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h1 className="text-xl">Sign in</h1>
        </CardTitle>
        <CardDescription>Welcome back to DevPulse AI.</CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={onSubmit} noValidate className="grid gap-4">
          {formError && (
            <Alert variant="destructive">
              <CircleAlertIcon />
              <AlertDescription>{formError}</AlertDescription>
            </Alert>
          )}

          <FormField label="Email" error={errors.email?.message}>
            <Input type="email" autoComplete="email" autoFocus {...register('email')} />
          </FormField>

          <FormField label="Password" error={errors.password?.message}>
            <PasswordInput autoComplete="current-password" {...register('password')} />
          </FormField>

          <Button type="submit" disabled={isSubmitting}>
            {isSubmitting && <Loader2Icon className="animate-spin" />}
            Sign in
          </Button>

          <p className="text-muted-foreground text-center text-sm">
            New to DevPulse?{' '}
            <Link
              to="/register"
              className="text-foreground font-medium underline-offset-4 hover:underline"
            >
              Create an account
            </Link>
          </p>
        </form>
      </CardContent>
    </Card>
  )
}
