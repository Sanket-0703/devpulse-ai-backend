import { MenuIcon } from 'lucide-react'
import { useState } from 'react'
import { Outlet } from 'react-router'

import { Button } from '@/components/ui/button'
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetTrigger } from '@/components/ui/sheet'
import { BackendStatusBadge } from '@/features/system/BackendStatusBadge'

import { Logo } from './Logo'
import { SidebarNav } from './SidebarNav'
import { ThemeToggle } from './ThemeToggle'

/**
 * Shell for every signed-in page: fixed sidebar on desktop, a drawer on
 * mobile, and a top bar. Pages render into <Outlet />.
 */
export function AppLayout() {
  const [mobileNavOpen, setMobileNavOpen] = useState(false)

  return (
    <div className="bg-background min-h-svh md:grid md:grid-cols-[15rem_1fr]">
      <a
        href="#main"
        className="bg-background sr-only z-50 rounded-md px-3 py-2 focus:not-sr-only focus:absolute focus:top-2 focus:left-2"
      >
        Skip to content
      </a>

      <aside className="bg-sidebar hidden border-r md:flex md:flex-col md:gap-6 md:p-4">
        <Logo />
        <SidebarNav />
      </aside>

      <div className="flex min-w-0 flex-col">
        <header className="bg-background/80 sticky top-0 z-10 flex h-14 items-center gap-2 border-b px-4 backdrop-blur">
          <Sheet open={mobileNavOpen} onOpenChange={setMobileNavOpen}>
            <SheetTrigger asChild>
              <Button variant="ghost" size="icon" className="md:hidden" aria-label="Open menu">
                <MenuIcon className="size-5" />
              </Button>
            </SheetTrigger>
            <SheetContent side="left" className="w-64 gap-6 p-4">
              <SheetHeader className="p-0">
                <SheetTitle asChild>
                  <div>
                    <Logo />
                  </div>
                </SheetTitle>
              </SheetHeader>
              <SidebarNav onNavigate={() => setMobileNavOpen(false)} />
            </SheetContent>
          </Sheet>

          <div className="md:hidden">
            <Logo />
          </div>

          <div className="ml-auto flex items-center gap-2">
            <BackendStatusBadge />
            <ThemeToggle />
          </div>
        </header>

        <main id="main" className="mx-auto w-full max-w-6xl flex-1 p-4 md:p-8">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
