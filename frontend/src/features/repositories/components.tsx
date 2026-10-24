import { CircleAlertIcon, Loader2Icon, RefreshCwIcon } from 'lucide-react'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Switch } from '@/components/ui/switch'
import { timeAgo } from '@/lib/time'

import type { Repository, SyncStatus } from './api'
import { useStartSync, useToggleReview } from './hooks'

/** "Synced 5 minutes ago · Sync now" (or "Syncing…", or the last error). */
export function SyncControl({ status }: { status: SyncStatus }) {
  const start = useStartSync()
  const running = status.status === 'RUNNING' || start.isPending

  return (
    <div className="flex flex-wrap items-center justify-end gap-3">
      <p className="text-muted-foreground text-sm" aria-live="polite">
        {running ? (
          'Syncing with GitHub…'
        ) : status.status === 'FAILED' ? (
          <span className="text-destructive inline-flex items-center gap-1">
            <CircleAlertIcon className="size-4" aria-hidden />
            Last sync failed: {status.error}
          </span>
        ) : status.lastSyncedAt ? (
          `Synced ${timeAgo(status.lastSyncedAt)}`
        ) : (
          'Not synced yet'
        )}
      </p>
      <Button variant="outline" onClick={() => start.mutate()} disabled={running}>
        {running ? <Loader2Icon className="animate-spin" /> : <RefreshCwIcon />}
        Sync now
      </Button>
    </div>
  )
}

export function RepositoryBadges({ repository }: { repository: Repository }) {
  return (
    <>
      {repository.isPrivate && <Badge variant="secondary">Private</Badge>}
      {repository.fork && <Badge variant="outline">Fork</Badge>}
      {repository.archived && <Badge variant="outline">Archived</Badge>}
    </>
  )
}

/** The per-repository "AI review" switch (optimistic). */
export function ReviewSwitch({ repository }: { repository: Repository }) {
  const toggle = useToggleReview()

  return (
    <Switch
      checked={repository.reviewEnabled}
      onCheckedChange={(checked) => toggle.mutate({ id: repository.id, reviewEnabled: checked })}
      aria-label={`AI review for ${repository.fullName}`}
    />
  )
}
