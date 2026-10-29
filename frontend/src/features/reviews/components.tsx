import {
  CircleCheckIcon,
  CircleXIcon,
  EyeIcon,
  EyeOffIcon,
  Loader2Icon,
  ShieldAlertIcon,
  SparklesIcon,
} from 'lucide-react'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

import type { Finding, Review, Severity } from './api'

const SEVERITY_STYLE: Record<Severity, string> = {
  CRITICAL: 'bg-red-600 text-white dark:bg-red-500',
  HIGH: 'bg-orange-500/15 text-orange-700 dark:text-orange-300',
  MEDIUM: 'bg-amber-500/15 text-amber-700 dark:text-amber-300',
  LOW: 'bg-sky-500/15 text-sky-700 dark:text-sky-300',
}

const label = (value: string) => value.charAt(0) + value.slice(1).toLowerCase().replace('_', ' ')

export function SeverityBadge({ severity }: { severity: Severity }) {
  return <Badge className={SEVERITY_STYLE[severity]}>{label(severity)}</Badge>
}

export function ReviewStatusBadge({ review }: { review: Pick<Review, 'status'> }) {
  switch (review.status) {
    case 'QUEUED':
    case 'RUNNING':
      return (
        <Badge variant="secondary" className="gap-1">
          <Loader2Icon className="animate-spin" aria-hidden />
          {review.status === 'QUEUED' ? 'Queued' : 'Reviewing'}
        </Badge>
      )
    case 'SUCCEEDED':
      return (
        <Badge variant="outline" className="gap-1">
          <CircleCheckIcon className="text-emerald-600" aria-hidden />
          Done
        </Badge>
      )
    case 'FAILED':
      return (
        <Badge variant="destructive" className="gap-1">
          <CircleXIcon aria-hidden />
          Failed
        </Badge>
      )
  }
}

/** "1 critical · 2 high · …"; only severities that occur, or "No open findings". */
export function FindingCountBadges({ counts }: { counts: Review['openFindings'] }) {
  const items = (
    [
      ['CRITICAL', counts.critical],
      ['HIGH', counts.high],
      ['MEDIUM', counts.medium],
      ['LOW', counts.low],
    ] as const
  ).filter(([, n]) => n > 0)

  if (items.length === 0) {
    return <span className="text-muted-foreground text-sm">No open findings</span>
  }
  return (
    <span className="flex flex-wrap gap-1">
      {items.map(([severity, n]) => (
        <Badge key={severity} className={SEVERITY_STYLE[severity]}>
          {n} {severity.toLowerCase()}
        </Badge>
      ))}
    </span>
  )
}

/**
 * One finding: severity, where it came from (AI or a security rule), what's
 * wrong and how to fix it, with Dismiss / Restore.
 */
export function FindingCard({
  finding,
  onToggleDismissed,
  pending,
  showLocation = false,
}: {
  finding: Finding
  onToggleDismissed?: (finding: Finding) => void
  pending?: boolean
  /** Show "file:line" (when the card isn't already next to that line). */
  showLocation?: boolean
}) {
  return (
    <article
      id={`finding-${finding.id}`}
      aria-label={`${label(finding.severity)}: ${finding.title}`}
      className={cn(
        'bg-background space-y-1.5 rounded-md border p-3 font-sans text-sm whitespace-normal',
        finding.dismissed && 'opacity-60',
      )}
    >
      <div className="flex flex-wrap items-center gap-2">
        <SeverityBadge severity={finding.severity} />
        <Badge variant="outline" className="gap-1">
          {finding.source === 'RULE' ? (
            <ShieldAlertIcon aria-hidden />
          ) : (
            <SparklesIcon aria-hidden />
          )}
          {finding.source === 'RULE' ? 'Security rule' : 'AI'}
        </Badge>
        <span className="text-muted-foreground text-xs">{label(finding.category)}</span>
        <h3 className={cn('font-medium', finding.dismissed && 'line-through')}>{finding.title}</h3>
        {onToggleDismissed && (
          <Button
            variant="ghost"
            size="sm"
            className="ml-auto h-7"
            disabled={pending}
            onClick={() => onToggleDismissed(finding)}
          >
            {finding.dismissed ? <EyeIcon /> : <EyeOffIcon />}
            {finding.dismissed ? 'Restore' : 'Dismiss'}
          </Button>
        )}
      </div>
      {showLocation && (
        <p className="text-muted-foreground font-mono text-xs break-all">
          {finding.filePath}
          {finding.line != null && `:${finding.line}`}
        </p>
      )}
      <p>{finding.description}</p>
      {finding.recommendation && (
        <p className="text-muted-foreground">
          <span className="text-foreground font-medium">Fix: </span>
          {finding.recommendation}
        </p>
      )}
    </article>
  )
}
