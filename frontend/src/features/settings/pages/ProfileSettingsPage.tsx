import { zodResolver } from '@hookform/resolvers/zod'
import { CircleAlertIcon, Loader2Icon } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { toast } from 'sonner'

import { FormField } from '@/components/common/FormField'
import { PasswordInput } from '@/components/common/PasswordInput'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { useAuth, useCurrentUser } from '@/features/auth/authContext'
import { ApiRequestError, errorMessage } from '@/lib/api/errors'
import { applyServerFieldErrors } from '@/lib/forms'

import { updateProfile } from '../api'
import { normalizeEmail, type ProfileValues, profileSchema } from '../schemas'

const SERVER_FIELDS = ['name', 'email', 'currentPassword'] as const

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'long' })

export function ProfileSettingsPage() {
  const user = useCurrentUser()
  const { updateUser } = useAuth()
  const [formError, setFormError] = useState<string | null>(null)

  // Rebuilt when these change, because the email rules depend on them.
  const schema = useMemo(
    () => profileSchema(user.email, user.hasPassword),
    [user.email, user.hasPassword],
  )

  const {
    register,
    handleSubmit,
    setError,
    reset,
    control,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<ProfileValues>({
    resolver: zodResolver(schema),
    defaultValues: { name: user.name, email: user.email, currentPassword: '' },
  })

  const watchedEmail = useWatch({ control, name: 'email' })
  const emailChanged = normalizeEmail(watchedEmail) !== user.email

  const onSubmit = handleSubmit(async ({ name, email, currentPassword }) => {
    setFormError(null)
    try {
      const updated = await updateProfile({
        name,
        email,
        currentPassword: emailChanged ? currentPassword : undefined,
      })
      updateUser(updated)
      // New baseline: the form is "clean" again with the saved values.
      reset({ name: updated.name, email: updated.email, currentPassword: '' })
      toast.success('Profile updated')
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
      {/* "contents": the form adds no box, so the card still spaces its sections */}
      <form onSubmit={onSubmit} noValidate className="contents">
        <CardHeader>
          <CardTitle>
            <h2>Profile</h2>
          </CardTitle>
          <CardDescription>
            Member since {dateFormat.format(new Date(user.createdAt))}
          </CardDescription>
        </CardHeader>

        <CardContent className="grid gap-4">
          {formError && (
            <Alert variant="destructive">
              <CircleAlertIcon />
              <AlertDescription>{formError}</AlertDescription>
            </Alert>
          )}

          <FormField label="Name" error={errors.name?.message}>
            <Input autoComplete="name" {...register('name')} />
          </FormField>

          <FormField label="Email" error={errors.email?.message}>
            <Input type="email" autoComplete="email" {...register('email')} />
          </FormField>

          {emailChanged && user.hasPassword && (
            <FormField
              label="Current password"
              error={errors.currentPassword?.message}
              hint="Needed to change your email."
            >
              <PasswordInput autoComplete="current-password" {...register('currentPassword')} />
            </FormField>
          )}
        </CardContent>

        <CardFooter className="justify-end gap-2">
          <Button
            type="button"
            variant="ghost"
            disabled={!isDirty || isSubmitting}
            onClick={() => reset()}
          >
            Cancel
          </Button>
          <Button type="submit" disabled={!isDirty || isSubmitting}>
            {isSubmitting && <Loader2Icon className="animate-spin" />}
            Save changes
          </Button>
        </CardFooter>
      </form>
    </Card>
  )
}
