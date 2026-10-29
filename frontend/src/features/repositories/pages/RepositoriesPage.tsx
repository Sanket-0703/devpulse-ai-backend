import { FolderGit2Icon, GitForkIcon, SearchIcon, SearchXIcon } from 'lucide-react'
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'

import { EmptyState } from '@/components/common/EmptyState'
import { PageHeader } from '@/components/common/PageHeader'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { useDebouncedCallback } from '@/hooks/useDebouncedCallback'
import { timeAgo } from '@/lib/time'
import { cn } from '@/lib/utils'

import type { Repository, RepositoryListParams, RepositorySort, ReviewFilter } from '../api'
import { RepositoryBadges, ReviewSwitch, SyncControl } from '../components'
import { useRepositories, useSyncStatus } from '../hooks'

const FILTERS: { value: ReviewFilter; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'on', label: 'Review on' },
  { value: 'off', label: 'Review off' },
]

const SORTS: { value: RepositorySort; label: string }[] = [
  { value: 'NAME', label: 'Name' },
  { value: 'PUSHED', label: 'Recently pushed' },
]

/**
 * Search, filter, sort and page live in the URL (?q=&filter=&sort=&page=),
 * so reload, back/forward and shared links keep the same view.
 */
function useListParams() {
  const [searchParams, setSearchParams] = useSearchParams()
  const params: RepositoryListParams = {
    q: searchParams.get('q') ?? '',
    filter: (searchParams.get('filter') as ReviewFilter) || 'all',
    sort: (searchParams.get('sort') as RepositorySort) || 'NAME',
    page: Math.max(Number(searchParams.get('page') ?? 1) - 1, 0), // 1-based in the URL
  }

  const update = (changes: Partial<RepositoryListParams>) => {
    const next = { ...params, page: 0, ...changes } // changing a filter resets to page 1
    const query = new URLSearchParams()
    if (next.q) query.set('q', next.q)
    if (next.filter !== 'all') query.set('filter', next.filter)
    if (next.sort !== 'NAME') query.set('sort', next.sort)
    if (next.page > 0) query.set('page', String(next.page + 1))
    setSearchParams(query, { replace: true })
  }

  return [params, update] as const
}

export function RepositoriesPage() {
  const sync = useSyncStatus()
  const [params, update] = useListParams()

  // The input updates immediately; the URL (and the request) 300 ms after typing stops.
  const [search, setSearch] = useState(params.q)
  const updateQuery = useDebouncedCallback((q: string) => update({ q }))
  // Keep the input in step when the URL changes on its own (back/forward).
  const [urlQuery, setUrlQuery] = useState(params.q)
  if (urlQuery !== params.q) {
    setUrlQuery(params.q)
    setSearch(params.q)
  }

  const status = sync.data
  const ready = status?.connected === true && status.installed
  const repositories = useRepositories(params, ready)

  return (
    <>
      <PageHeader
        title="Repositories"
        description="Repositories the DevPulse app can see. Turn on AI review for the ones you want reviewed."
        actions={ready && status ? <SyncControl status={status} /> : undefined}
      />

      {sync.isPending ? (
        <ListSkeleton />
      ) : !status?.connected ? (
        <EmptyState
          icon={FolderGit2Icon}
          title="Connect GitHub first"
          description="DevPulse shows the repositories you give its GitHub app access to."
          action={
            <Button asChild>
              <Link to="/settings/github">Go to Settings → GitHub</Link>
            </Button>
          }
        />
      ) : !status.installed ? (
        <EmptyState
          icon={FolderGit2Icon}
          title="Install the DevPulse app"
          description="Choose which of your repositories DevPulse can see by installing its GitHub app."
          action={
            <Button asChild>
              <Link to="/settings/github">Install on repositories</Link>
            </Button>
          }
        />
      ) : (
        <>
          <div className="mb-4 flex flex-col gap-3 md:flex-row md:items-center">
            <div className="relative md:max-w-xs md:flex-1">
              <SearchIcon
                className="text-muted-foreground pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2"
                aria-hidden
              />
              <Input
                type="search"
                placeholder="Search repositories"
                aria-label="Search repositories"
                className="pl-9"
                value={search}
                onChange={(e) => {
                  setSearch(e.target.value)
                  updateQuery(e.target.value)
                }}
              />
            </div>
            <SegmentedControl
              label="Review filter"
              options={FILTERS}
              value={params.filter}
              onChange={(filter) => update({ filter })}
            />
            <SegmentedControl
              label="Sort by"
              options={SORTS}
              value={params.sort}
              onChange={(sort) => update({ sort })}
            />
          </div>

          <RepositoryList
            query={repositories}
            syncing={status.status === 'RUNNING'}
            filtered={params.q !== '' || params.filter !== 'all'}
            onPage={(page) => update({ ...params, page })}
          />
        </>
      )}
    </>
  )
}

function RepositoryList({
  query,
  syncing,
  filtered,
  onPage,
}: {
  query: ReturnType<typeof useRepositories>
  syncing: boolean
  filtered: boolean
  onPage: (page: number) => void
}) {
  if (query.isPending) return <ListSkeleton />
  if (query.isError) {
    return (
      <EmptyState
        icon={SearchXIcon}
        title="Couldn't load repositories"
        description="Something went wrong. Try again in a moment."
        action={<Button onClick={() => void query.refetch()}>Retry</Button>}
      />
    )
  }

  const page = query.data
  if (page.totalElements === 0) {
    if (filtered) {
      return (
        <EmptyState
          icon={SearchXIcon}
          title="No repositories match"
          description="Try a different search or filter."
        />
      )
    }
    return syncing ? (
      <ListSkeleton />
    ) : (
      <EmptyState
        icon={FolderGit2Icon}
        title="No repositories yet"
        description="Press Sync now, or give the DevPulse app access to more repositories on GitHub."
      />
    )
  }

  return (
    <>
      <Card className={cn('gap-0 py-0', query.isPlaceholderData && 'opacity-60')}>
        <ul className="divide-y">
          {page.content.map((repository) => (
            <RepositoryRow key={repository.id} repository={repository} />
          ))}
        </ul>
      </Card>

      {page.totalPages > 1 && (
        <nav aria-label="Pagination" className="mt-4 flex items-center justify-between gap-2">
          <p className="text-muted-foreground text-sm">
            Page {page.page + 1} of {page.totalPages} · {page.totalElements} repositories
          </p>
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={page.page === 0}
              onClick={() => onPage(page.page - 1)}
            >
              Previous
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={page.page + 1 >= page.totalPages}
              onClick={() => onPage(page.page + 1)}
            >
              Next
            </Button>
          </div>
        </nav>
      )}
    </>
  )
}

function RepositoryRow({ repository }: { repository: Repository }) {
  return (
    <li className="flex items-center gap-4 px-4 py-3">
      <div className="min-w-0 flex-1 space-y-1">
        <div className="flex flex-wrap items-center gap-2">
          <Link
            to={`/repositories/${repository.id}`}
            className="truncate font-medium underline-offset-4 hover:underline"
          >
            {repository.fullName}
          </Link>
          <RepositoryBadges repository={repository} />
        </div>
        {repository.description && (
          <p className="text-muted-foreground line-clamp-1 text-sm">{repository.description}</p>
        )}
        <p className="text-muted-foreground flex flex-wrap items-center gap-x-3 text-xs">
          {repository.language && <span>{repository.language}</span>}
          {repository.fork && (
            <span className="inline-flex items-center gap-1">
              <GitForkIcon className="size-3" aria-hidden /> fork
            </span>
          )}
          {repository.pushedAt && <span>Pushed {timeAgo(repository.pushedAt)}</span>}
        </p>
      </div>
      <label className="text-muted-foreground flex shrink-0 items-center gap-2 text-sm">
        <span className="hidden sm:inline">AI review</span>
        <ReviewSwitch repository={repository} />
      </label>
    </li>
  )
}

/** A row of toggle buttons; exactly one is pressed. */
function SegmentedControl<T extends string>({
  label,
  options,
  value,
  onChange,
}: {
  label: string
  options: { value: T; label: string }[]
  value: T
  onChange: (value: T) => void
}) {
  return (
    <div role="group" aria-label={label} className="bg-muted inline-flex rounded-md p-0.5">
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          aria-pressed={value === option.value}
          onClick={() => onChange(option.value)}
          className={cn(
            'rounded-sm px-3 py-1.5 text-sm font-medium transition-colors',
            'focus-visible:ring-ring outline-none focus-visible:ring-2',
            value === option.value
              ? 'bg-background text-foreground shadow-sm'
              : 'text-muted-foreground hover:text-foreground',
          )}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}

function ListSkeleton() {
  return (
    <Card className="gap-0 py-0" aria-busy="true" aria-label="Loading repositories">
      {[0, 1, 2].map((i) => (
        <div key={i} className="flex items-center gap-4 border-b px-4 py-4 last:border-b-0">
          <div className="flex-1 space-y-2">
            <Skeleton className="h-4 w-48" />
            <Skeleton className="h-3 w-72" />
          </div>
          <Skeleton className="h-5 w-9 rounded-full" />
        </div>
      ))}
    </Card>
  )
}
