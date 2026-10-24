import { CircleAlertIcon, Loader2Icon } from 'lucide-react'
import { useEffect } from 'react'
import { Link, Navigate, useSearchParams } from 'react-router'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { useAuth } from '@/features/auth/authContext'

import { githubErrorMessage } from './callbackReasons'

/**
 * Where the backend sends the browser after GitHub:
 *   ?result=signed-in(&new=true) → a session cookie was set; AuthProvider
 *     restored it on page load, so go to the dashboard
 *   ?result=connected            → back to Settings → GitHub
 *   ?result=error&reason=…       → explain what happened
 */
export function GitHubCallbackPage() {
  const [params] = useSearchParams()
  const auth = useAuth()
  const result = params.get('result')
  const isNewAccount = params.get('new') === 'true'

  const succeeded =
    (result === 'signed-in' || result === 'connected') && auth.status === 'authenticated'

  // A fixed toast id: React StrictMode runs effects twice in development.
  useEffect(() => {
    if (!succeeded) return
    if (result === 'signed-in') {
      toast.success(isNewAccount ? 'Welcome to DevPulse!' : 'Signed in with GitHub', {
        id: 'github-callback',
      })
    } else {
      toast.success('GitHub connected', { id: 'github-callback' })
    }
  }, [succeeded, result, isNewAccount])

  if (auth.status === 'loading') {
    return (
      <div className="flex min-h-svh items-center justify-center" role="status">
        <Loader2Icon className="text-muted-foreground size-6 animate-spin" aria-hidden />
        <span className="sr-only">Finishing GitHub sign-in…</span>
      </div>
    )
  }

  if (succeeded) {
    return <Navigate to={result === 'signed-in' ? '/dashboard' : '/settings/github'} replace />
  }

  const message =
    result === 'error'
      ? githubErrorMessage(params.get('reason'))
      : 'Your session couldn’t be started. Please sign in again.'
  const signedIn = auth.status === 'authenticated'

  return (
    <div className="bg-muted/40 flex min-h-svh items-center justify-center p-4">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <CircleAlertIcon className="text-destructive size-5" aria-hidden />
            <h1 className="text-lg">GitHub didn&apos;t connect</h1>
          </CardTitle>
          <CardDescription role="alert">{message}</CardDescription>
        </CardHeader>
        <CardContent>
          <Button asChild className="w-full">
            {signedIn ? (
              <Link to="/settings/github">Back to settings</Link>
            ) : (
              <Link to="/login">Back to sign in</Link>
            )}
          </Button>
        </CardContent>
      </Card>
    </div>
  )
}
