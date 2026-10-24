import { zodResolver } from '@hookform/resolvers/zod'
import { CircleAlertIcon, Loader2Icon, LogOutIcon } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'

import { FormField } from '@/components/common/FormField'
import { PasswordInput } from '@/components/common/PasswordInput'
import { Alert, AlertDescription } from '@/components/ui/alert'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { useAuth, useCurrentUser } from '@/features/auth/authContext'
import { errorMessage } from '@/lib/api/errors'
import { applyServerFieldErrors } from '@/lib/forms'

import { changePassword } from '../api'
import { type ChangePasswordValues, changePasswordSchema } from '../schemas'

const SERVER_FIELDS = ['currentPassword', 'newPassword'] as const

export function SecuritySettingsPage() {
  return (
    <>
      <ChangePasswordCard />
      <SignOutEverywhereCard />
    </>
  )
}

function ChangePasswordCard() {
  const user = useCurrentUser()
  const { updateUser } = useAuth()
  const [formError, setFormError] = useState<string | null>(null)
  // Accounts created with "Sign in with GitHub" set their first password here.
  const hasPassword = user.hasPassword
  const schema = useMemo(() => changePasswordSchema(hasPassword), [hasPassword])

  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<ChangePasswordValues>({
    resolver: zodResolver(schema),
    defaultValues: { currentPassword: '', newPassword: '', confirmPassword: '' },
  })

  const onSubmit = handleSubmit(async ({ currentPassword, newPassword }) => {
    setFormError(null)
    try {
      const updated = await changePassword({
        currentPassword: hasPassword ? currentPassword : undefined,
        newPassword,
      })
      updateUser(updated)
      reset()
      toast.success(
        hasPassword
          ? 'Password changed. Your other devices have been signed out.'
          : 'Password set. You can now also sign in with your email and password.',
      )
    } catch (error) {
      if (applyServerFieldErrors(error, setError, SERVER_FIELDS)) return
      setFormError(errorMessage(error))
    }
  })

  return (
    <Card>
      {/* "contents": the form adds no box, so the card still spaces its sections */}
      <form onSubmit={onSubmit} noValidate className="contents">
        <CardHeader>
          <CardTitle>
            <h2>{hasPassword ? 'Change password' : 'Set a password'}</h2>
          </CardTitle>
          <CardDescription>
            {hasPassword
              ? 'You stay signed in here; every other device is signed out.'
              : 'You signed up with GitHub. Add a password to also sign in with your email.'}
          </CardDescription>
        </CardHeader>

        <CardContent className="grid gap-4">
          {formError && (
            <Alert variant="destructive">
              <CircleAlertIcon />
              <AlertDescription>{formError}</AlertDescription>
            </Alert>
          )}

          {hasPassword && (
            <FormField label="Current password" error={errors.currentPassword?.message}>
              <PasswordInput autoComplete="current-password" {...register('currentPassword')} />
            </FormField>
          )}

          <FormField
            label="New password"
            error={errors.newPassword?.message}
            hint="8 to 72 characters"
          >
            <PasswordInput autoComplete="new-password" {...register('newPassword')} />
          </FormField>

          <FormField label="Confirm new password" error={errors.confirmPassword?.message}>
            <PasswordInput autoComplete="new-password" {...register('confirmPassword')} />
          </FormField>
        </CardContent>

        <CardFooter className="justify-end">
          <Button type="submit" disabled={isSubmitting}>
            {isSubmitting && <Loader2Icon className="animate-spin" />}
            {hasPassword ? 'Change password' : 'Set password'}
          </Button>
        </CardFooter>
      </form>
    </Card>
  )
}

function SignOutEverywhereCard() {
  const { logoutEverywhere } = useAuth()
  const [pending, setPending] = useState(false)

  // After success the session is gone: <RequireAuth> redirects to /login.
  async function confirm() {
    setPending(true)
    try {
      await logoutEverywhere()
      toast.success('Signed out on every device.')
    } catch (error) {
      toast.error(errorMessage(error))
      setPending(false)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2>Sessions</h2>
        </CardTitle>
        <CardDescription>
          Lost a device or signed in on a shared computer? End every session at once.
        </CardDescription>
      </CardHeader>
      <CardFooter className="justify-end">
        <AlertDialog>
          <AlertDialogTrigger asChild>
            <Button variant="destructive" disabled={pending}>
              {pending ? <Loader2Icon className="animate-spin" /> : <LogOutIcon />}
              Sign out everywhere
            </Button>
          </AlertDialogTrigger>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>Sign out on every device?</AlertDialogTitle>
              <AlertDialogDescription>
                This ends every session of your account, including this one. You'll need to sign in
                again everywhere.
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>Cancel</AlertDialogCancel>
              <AlertDialogAction onClick={() => void confirm()}>
                Sign out everywhere
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </CardFooter>
    </Card>
  )
}
