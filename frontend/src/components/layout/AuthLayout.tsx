import { Outlet } from 'react-router'

import { Logo } from './Logo'
import { ThemeToggle } from './ThemeToggle'

/** Centered card layout for the sign-in and sign-up pages. */
export function AuthLayout() {
  return (
    <div className="bg-muted/40 flex min-h-svh flex-col">
      <header className="flex h-14 items-center justify-between px-4">
        <Logo />
        <ThemeToggle />
      </header>
      <main className="flex flex-1 items-center justify-center p-4">
        <div className="w-full max-w-sm">
          <Outlet />
        </div>
      </main>
    </div>
  )
}
