import { createContext, useContext } from 'react'

import type { LoginBody, RegisterBody, User } from './api'

export type AuthState =
  { status: 'loading' } | { status: 'authenticated'; user: User } | { status: 'anonymous' }

export type AuthContextValue = AuthState & {
  login: (body: LoginBody) => Promise<User>
  /** Creates the account, then signs in with the same credentials. */
  register: (body: RegisterBody) => Promise<User>
  logout: () => Promise<void>
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth must be used inside <AuthProvider>')
  return value
}

/** The signed-in user; only call inside routes guarded by <RequireAuth>. */
export function useCurrentUser(): User {
  const auth = useAuth()
  if (auth.status !== 'authenticated') throw new Error('useCurrentUser needs a signed-in user')
  return auth.user
}
