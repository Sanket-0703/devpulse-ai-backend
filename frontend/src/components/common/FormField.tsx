import { type ReactElement, cloneElement, useId } from 'react'

import { Label } from '@/components/ui/label'

type FormFieldProps = {
  label: string
  /** Validation message from react-hook-form or the server. */
  error?: string
  hint?: string
  /** The input; gets id and aria-* wiring so screen readers announce the error. */
  children: ReactElement<Record<string, unknown>>
}

export function FormField({ label, error, hint, children }: FormFieldProps) {
  const id = useId()
  const messageId = `${id}-message`
  const message = error ?? hint

  return (
    <div className="grid gap-2">
      <Label htmlFor={id}>{label}</Label>
      {cloneElement(children, {
        id,
        'aria-invalid': error ? true : undefined,
        'aria-describedby': message ? messageId : undefined,
      })}
      {message && (
        <p
          id={messageId}
          className={error ? 'text-destructive text-sm' : 'text-muted-foreground text-sm'}
        >
          {message}
        </p>
      )}
    </div>
  )
}
