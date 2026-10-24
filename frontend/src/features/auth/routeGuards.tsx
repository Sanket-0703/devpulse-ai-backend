import { Loader2Icon } from 'lucide-react'
import { Navigate, Outlet, useLocation } from 'react-router'

import { useAuth } from './authContext'

/** Where to send the user after signing in: the page they originally asked for. */
type FromState = { from?: { pathname: string; search?: string } } | null

function FullPageSpinner() {
  return (
    <div className="flex min-h-svh items-center justify-center" role="status">
      <Loader2Icon className="text-muted-foreground size-6 animate-spin" aria-hidden />
      <span className="sr-only">Loading…</span>
    </div>
  )
}

/** Renders its child routes only for signed-in users; others go to /login. */
export function RequireAuth() {
  const auth = useAuth()
  const location = useLocation()

  if (auth.status === 'loading') return <FullPageSpinner />
  if (auth.status === 'anonymous') {
    return <Navigate to="/login" replace state={{ from: location } satisfies FromState} />
  }
  return <Outlet />
}

/** Login and register pages: signed-in users are sent on into the app. */
export function GuestOnly() {
  const auth = useAuth()
  const location = useLocation()

  if (auth.status === 'loading') return <FullPageSpinner />
  if (auth.status === 'authenticated') {
    const from = (location.state as FromState)?.from
    return <Navigate to={from ? `${from.pathname}${from.search ?? ''}` : '/dashboard'} replace />
  }
  return <Outlet />
}
