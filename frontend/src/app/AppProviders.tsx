import { QueryClientProvider } from '@tanstack/react-query'
import { type ReactNode, useState } from 'react'

import { Toaster } from '@/components/ui/sonner'
import { TooltipProvider } from '@/components/ui/tooltip'
import { AuthProvider } from '@/features/auth/AuthProvider'

import { createQueryClient } from './queryClient'
import { ThemeProvider } from './theme/ThemeProvider'

/** Everything the app needs around the router: server-data cache, theme, tooltips, toasts. */
export function AppProviders({ children }: { children: ReactNode }) {
  const [queryClient] = useState(createQueryClient)

  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <TooltipProvider>
          <AuthProvider>{children}</AuthProvider>
          <Toaster richColors closeButton />
        </TooltipProvider>
      </ThemeProvider>
    </QueryClientProvider>
  )
}
