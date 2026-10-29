import {
  GitPullRequestIcon,
  Loader2Icon,
  RefreshCwIcon,
  SearchIcon,
  SearchXIcon,
} from 'lucide-react'
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'

import { EmptyState } from '@/components/common/EmptyState'
import { Pagination } from '@/components/common/Pagination'
import { SegmentedControl } from '@/components/common/SegmentedControl'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import type { Repository } from '@/features/repositories/api'
import { useDebouncedCallback } from '@/hooks/useDebouncedCallback'
import { timeAgo } from '@/lib/time'
import { cn } from '@/lib/utils'

import type { PullRequest, PullRequestListParams, StateFilter } from './api'
import { AuthorAvatar, PullRequestStateIcon } from './components'
import { usePullRequests, useSyncPullRequests } from './hooks'

const STATES: { value: StateFilter; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'OPEN', label: 'Open' },
  { value: 'MERGED', label: 'Merged' },
  { value: 'CLOSED', label: 'Closed' },
]

/** State, search and page live in the URL (?state=&q=&page=), like the repository list. */
function useListParams() {
  const [searchParams, setSearchParams] = useSearchParams()
  const params: PullRequestListParams = {
    q: searchParams.get('q') ?? '',
    state: (searchParams.get('state') as StateFilter) || 'all',
    page: Math.max(Number(searchParams.get('page') ?? 1) - 1, 0), // 1-based in the URL
  }

  const update = (changes: Partial<PullRequestListParams>) => {
    const next = { ...params, page: 0, ...changes } // changing a filter resets to page 1
    const query = new URLSearchParams()
    if (next.q) query.set('q', next.q)
    if (next.state !== 'all') query.set('state', next.state)
    if (next.page > 0) query.set('page', String(next.page + 1))
    setSearchParams(query, { replace: true })
  }

  return [params, update] as const
}

/** The "Pull requests" part of a repository page. */
export function PullRequestsSection({ repository }: { repository: Repository }) {
  const sync = useSyncPullRequests(repository.id)
  const neverSynced = !repository.pullRequestsSyncedAt

  return (
    <section aria-labelledby="pull-requests-heading" className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="pull-requests-heading" className="text-lg font-semibold">
          Pull requests
        </h2>
        <div className="flex items-center gap-3">
          <p className="text-muted-foreground text-sm" aria-live="polite">
            {sync.isPending
              ? 'Syncing with GitHub…'
              : repository.pullRequestsSyncedAt
                ? `Synced ${timeAgo(repository.pullRequestsSyncedAt)}`
                : 'Not synced yet'}
          </p>
          <Button variant="outline" onClick={() => sync.mutate()} disabled={sync.isPending}>
            {sync.isPending ? <Loader2Icon className="animate-spin" /> : <RefreshCwIcon />}
            Sync
          </Button>
        </div>
      </div>

      {neverSynced ? (
        <EmptyState
          icon={GitPullRequestIcon}
          title="Pull requests aren't synced yet"
          description="Fetch this repository's pull requests from GitHub. Later syncs only fetch what changed."
          action={
            <Button onClick={() => sync.mutate()} disabled={sync.isPending}>
              {sync.isPending && <Loader2Icon className="animate-spin" />}
              Sync pull requests
            </Button>
          }
        />
      ) : (
        <PullRequestBrowser repositoryId={repository.id} />
      )}
    </section>
  )
}

function PullRequestBrowser({ repositoryId }: { repositoryId: number }) {
  const [params, update] = useListParams()
  const query = usePullRequests(repositoryId, params)

  // The input updates immediately; the URL (and the request) 300 ms after typing stops.
  const [search, setSearch] = useState(params.q)
  const updateQuery = useDebouncedCallback((q: string) => update({ q }))
  // Keep the input in step when the URL changes on its own (back/forward).
  const [urlQuery, setUrlQuery] = useState(params.q)
  if (urlQuery !== params.q) {
    setUrlQuery(params.q)
    setSearch(params.q)
  }

  return (
    <>
      <div className="flex flex-col gap-3 md:flex-row md:items-center">
        <div className="relative md:max-w-xs md:flex-1">
          <SearchIcon
            className="text-muted-foreground pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2"
            aria-hidden
          />
          <Input
            type="search"
            placeholder="Title, author or #number"
            aria-label="Search pull requests"
            className="pl-9"
            value={search}
            onChange={(e) => {
              setSearch(e.target.value)
              updateQuery(e.target.value)
            }}
          />
        </div>
        <SegmentedControl
          label="State"
          options={STATES}
          value={params.state}
          onChange={(state) => update({ state })}
        />
      </div>

      {query.isPending ? (
        <ListSkeleton />
      ) : query.isError ? (
        <EmptyState
          icon={SearchXIcon}
          title="Couldn't load pull requests"
          description="Something went wrong. Try again in a moment."
          action={<Button onClick={() => void query.refetch()}>Retry</Button>}
        />
      ) : query.data.totalElements === 0 ? (
        params.q !== '' || params.state !== 'all' ? (
          <EmptyState
            icon={SearchXIcon}
            title="No pull requests match"
            description="Try a different search or state."
          />
        ) : (
          <EmptyState
            icon={GitPullRequestIcon}
            title="No pull requests yet"
            description="When someone opens a pull request on GitHub, press Sync to see it here."
          />
        )
      ) : (
        <>
          <Card className={cn('gap-0 py-0', query.isPlaceholderData && 'opacity-60')}>
            <ul className="divide-y">
              {query.data.content.map((pr) => (
                <PullRequestRow key={pr.id} repositoryId={repositoryId} pullRequest={pr} />
              ))}
            </ul>
          </Card>
          <Pagination
            page={query.data.page}
            totalPages={query.data.totalPages}
            totalElements={query.data.totalElements}
            noun="pull requests"
            onPage={(page) => update({ ...params, page })}
          />
        </>
      )}
    </>
  )
}

function PullRequestRow({
  repositoryId,
  pullRequest: pr,
}: {
  repositoryId: number
  pullRequest: PullRequest
}) {
  return (
    <li className="flex items-start gap-3 px-4 py-3">
      <PullRequestStateIcon pullRequest={pr} />
      <div className="min-w-0 flex-1 space-y-1">
        <Link
          to={`/repositories/${repositoryId}/pulls/${pr.number}`}
          className="font-medium break-words underline-offset-4 hover:underline"
        >
          {pr.title}
        </Link>
        <p className="text-muted-foreground flex flex-wrap items-center gap-x-2 text-xs">
          <span>#{pr.number}</span>
          <span className="inline-flex items-center gap-1">
            <AuthorAvatar login={pr.authorLogin} src={pr.authorAvatarUrl} />
            {pr.authorLogin}
          </span>
          <span>updated {timeAgo(pr.updatedAt)}</span>
          <span className="truncate font-mono">
            {pr.sourceBranch} → {pr.targetBranch}
          </span>
        </p>
      </div>
    </li>
  )
}

function ListSkeleton() {
  return (
    <Card className="gap-0 py-0" aria-busy="true" aria-label="Loading pull requests">
      {[0, 1, 2].map((i) => (
        <div key={i} className="flex items-start gap-3 border-b px-4 py-4 last:border-b-0">
          <Skeleton className="size-7 rounded-full" />
          <div className="flex-1 space-y-2">
            <Skeleton className="h-4 w-64" />
            <Skeleton className="h-3 w-80" />
          </div>
        </div>
      ))}
    </Card>
  )
}
