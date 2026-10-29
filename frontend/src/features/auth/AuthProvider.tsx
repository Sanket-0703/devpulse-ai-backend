import { useQueryClient } from '@tanstack/react-query'
import { type ReactNode, useCallback, useEffect, useMemo, useState } from 'react'
import { toast } from 'sonner'

import * as authApi from './api'
import { type AuthState, AuthContext } from './authContext'
import { onSessionEnded, refreshSession } from './session'

/**
 * Owns the signed-in state. On start-up it tries to restore the session from
 * the refresh-token cookie, so a page reload keeps you signed in.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [state, setState] = useState<AuthState>({ status: 'loading' })

  useEffect(() => {
    let cancelled = false
    refreshSession().then((session) => {
      if (cancelled) return
      setState(session ? { status: 'authenticated', user: session.user } : { status: 'anonymous' })
    })
    return () => {
      cancelled = true
    }
  }, [])

  // A refresh failed in the middle of using the app: the session is gone.
  useEffect(
    () =>
      onSessionEnded(() => {
        queryClient.clear()
        setState({ status: 'anonymous' })
        toast.info('Your session has ended. Please sign in again.')
      }),
    [queryClient],
  )

  const login = useCallback(async (body: authApi.LoginBody) => {
    const session = await authApi.login(body)
    setState({ status: 'authenticated', user: session.user })
    return session.user
  }, [])

  const loginDemo = useCallback(async () => {
    const session = await authApi.demoLogin()
    setState({ status: 'authenticated', user: session.user })
    return session.user
  }, [])

  const register = useCallback(
    async (body: authApi.RegisterBody) => {
      await authApi.register(body)
      return login({ email: body.email, password: body.password })
    },
    [login],
  )

  const logout = useCallback(async () => {
    await authApi.logout()
    queryClient.clear()
    setState({ status: 'anonymous' })
  }, [queryClient])

  const logoutEverywhere = useCallback(async () => {
    await authApi.logoutEverywhere()
    queryClient.clear()
    setState({ status: 'anonymous' })
  }, [queryClient])

  const updateUser = useCallback((user: authApi.User) => {
    setState((current) =>
      current.status === 'authenticated' ? { status: 'authenticated', user } : current,
    )
  }, [])

  const value = useMemo(
    () => ({ ...state, login, loginDemo, register, logout, logoutEverywhere, updateUser }),
    [state, login, loginDemo, register, logout, logoutEverywhere, updateUser],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
