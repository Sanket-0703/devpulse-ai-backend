import { ArrowLeftIcon, ExternalLinkIcon, FileSearchIcon } from 'lucide-react'
import { Link, useParams } from 'react-router'

import { EmptyState } from '@/components/common/EmptyState'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { PullRequestsSection } from '@/features/pull-requests/PullRequestsSection'
import { ApiRequestError } from '@/lib/api/errors'
import { timeAgo } from '@/lib/time'

import { RepositoryBadges, ReviewSwitch } from '../components'
import { useRepository } from '../hooks'

export function RepositoryDetailPage() {
  const id = Number(useParams().id)
  const repository = useRepository(id)

  if (repository.isPending) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-8 w-72" />
        <Skeleton className="h-32 w-full" />
      </div>
    )
  }

  if (repository.isError) {
    const notFound = repository.error instanceof ApiRequestError && repository.error.status === 404
    return (
      <EmptyState
        icon={FileSearchIcon}
        title={notFound ? 'Repository not found' : 'Couldn’t load this repository'}
        description={
          notFound
            ? 'It may have been removed from the DevPulse app on GitHub.'
            : 'Something went wrong. Try again in a moment.'
        }
        action={
          <Button asChild>
            <Link to="/repositories">Back to repositories</Link>
          </Button>
        }
      />
    )
  }

  const repo = repository.data
  return (
    <div className="space-y-6">
      <div className="space-y-3">
        <Link
          to="/repositories"
          className="text-muted-foreground hover:text-foreground inline-flex items-center gap-1 text-sm"
        >
          <ArrowLeftIcon className="size-4" aria-hidden /> Repositories
        </Link>
        <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
          <div className="space-y-1">
            <div className="flex flex-wrap items-center gap-2">
              <h1 className="text-2xl font-semibold tracking-tight break-all">{repo.fullName}</h1>
              <RepositoryBadges repository={repo} />
            </div>
            {repo.description && (
              <p className="text-muted-foreground text-sm">{repo.description}</p>
            )}
          </div>
          <Button asChild variant="outline">
            <a href={repo.htmlUrl} target="_blank" rel="noreferrer">
              Open on GitHub <ExternalLinkIcon />
            </a>
          </Button>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>
            <h2>AI review</h2>
          </CardTitle>
          <CardDescription>
            When on, DevPulse reviews this repository&apos;s pull requests for bugs and
            vulnerabilities (reviews arrive in Level 7).
          </CardDescription>
        </CardHeader>
        <CardContent>
          <label className="flex items-center gap-3 text-sm font-medium">
            <ReviewSwitch repository={repo} />
            {repo.reviewEnabled ? 'Review on' : 'Review off'}
          </label>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>
            <h2>Details</h2>
          </CardTitle>
        </CardHeader>
        <CardContent>
          <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
            <Detail label="Default branch">
              <Badge variant="outline">{repo.defaultBranch}</Badge>
            </Detail>
            <Detail label="Language">{repo.language ?? '—'}</Detail>
            <Detail label="Last push">{repo.pushedAt ? timeAgo(repo.pushedAt) : '—'}</Detail>
            <Detail label="Last synced">{timeAgo(repo.syncedAt)}</Detail>
          </dl>
        </CardContent>
      </Card>

      <PullRequestsSection repository={repo} />
    </div>
  )
}

function Detail({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="font-medium">{children}</dd>
    </div>
  )
}
