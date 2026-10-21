import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'

import { useBackendHealth } from './useBackendHealth'

const LABELS = {
  online: 'API online',
  offline: 'API offline',
  checking: 'Checking API…',
} as const

export function BackendStatusBadge() {
  const status = useBackendHealth()

  return (
    <Badge variant="outline" className="gap-1.5" aria-live="polite">
      <span
        aria-hidden
        className={cn(
          'size-2 rounded-full',
          status === 'online' && 'bg-emerald-500',
          status === 'offline' && 'bg-destructive',
          status === 'checking' && 'bg-muted-foreground animate-pulse',
        )}
      />
      {LABELS[status]}
    </Badge>
  )
}
