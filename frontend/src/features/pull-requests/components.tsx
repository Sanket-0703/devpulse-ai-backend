import {
  GitMergeIcon,
  GitPullRequestClosedIcon,
  GitPullRequestDraftIcon,
  GitPullRequestIcon,
  type LucideIcon,
} from 'lucide-react'

import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'

import type { PullRequest } from './api'

type Look = { label: string; icon: LucideIcon; className: string }

// GitHub's colours, so the states read at a glance: green open, purple merged.
const LOOKS: Record<'OPEN' | 'DRAFT' | 'MERGED' | 'CLOSED', Look> = {
  OPEN: {
    label: 'Open',
    icon: GitPullRequestIcon,
    className: 'bg-emerald-600/10 text-emerald-700 dark:bg-emerald-400/15 dark:text-emerald-300',
  },
  DRAFT: {
    label: 'Draft',
    icon: GitPullRequestDraftIcon,
    className: 'bg-muted text-muted-foreground',
  },
  MERGED: {
    label: 'Merged',
    icon: GitMergeIcon,
    className: 'bg-violet-600/10 text-violet-700 dark:bg-violet-400/15 dark:text-violet-300',
  },
  CLOSED: {
    label: 'Closed',
    icon: GitPullRequestClosedIcon,
    className: 'bg-rose-600/10 text-rose-700 dark:bg-rose-400/15 dark:text-rose-300',
  },
}

function lookOf(pr: Pick<PullRequest, 'state' | 'draft'>): Look {
  return pr.state === 'OPEN' && pr.draft ? LOOKS.DRAFT : LOOKS[pr.state]
}

/** "Open", "Draft", "Merged" or "Closed", with GitHub's icon and colour. */
export function PullRequestStateBadge({
  pullRequest,
}: {
  pullRequest: Pick<PullRequest, 'state' | 'draft'>
}) {
  const look = lookOf(pullRequest)
  const Icon = look.icon
  return (
    <Badge className={cn('gap-1', look.className)}>
      <Icon aria-hidden />
      {look.label}
    </Badge>
  )
}

/** The state icon alone, for dense lists (the label is for screen readers). */
export function PullRequestStateIcon({
  pullRequest,
}: {
  pullRequest: Pick<PullRequest, 'state' | 'draft'>
}) {
  const look = lookOf(pullRequest)
  const Icon = look.icon
  return (
    <span className={cn('inline-flex rounded-full p-1.5', look.className)}>
      <Icon className="size-4" aria-hidden />
      <span className="sr-only">{look.label}</span>
    </span>
  )
}

export function AuthorAvatar({ login, src }: { login: string; src?: string }) {
  return (
    <Avatar size="sm">
      {src && <AvatarImage src={src} alt="" />}
      <AvatarFallback className="text-[10px]">{login.slice(0, 2).toUpperCase()}</AvatarFallback>
    </Avatar>
  )
}
