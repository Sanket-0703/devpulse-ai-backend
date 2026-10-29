import {
  ArrowLeftIcon,
  ChevronRightIcon,
  CircleAlertIcon,
  ExternalLinkIcon,
  FileSearchIcon,
} from 'lucide-react'
import { Link, useParams } from 'react-router'

import { EmptyState } from '@/components/common/EmptyState'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { useRepository } from '@/features/repositories/hooks'
import { ApiRequestError, errorMessage } from '@/lib/api/errors'
import { timeAgo } from '@/lib/time'

import type { ChangedFile, PullRequest } from '../api'
import { AuthorAvatar, PullRequestStateBadge } from '../components'
import { DiffView } from '../DiffView'
import type { Finding } from '@/features/reviews/api'
import { FindingCard } from '@/features/reviews/components'
import { ReviewPanel } from '@/features/reviews/ReviewPanel'
import { type ReviewState, useReviewState } from '@/features/reviews/useReviewState'
import { usePullRequest, usePullRequestFiles } from '../hooks'

/** Files with more changed lines than this start collapsed, so big PRs stay fast. */
const COLLAPSE_ABOVE = 400

export function PullRequestDetailPage() {
  const params = useParams()
  const repositoryId = Number(params.id)
  const number = Number(params.number)
  const repository = useRepository(repositoryId)
  const detail = usePullRequest(repositoryId, number)
  const review = useReviewState(repositoryId, number)

  const backLink = (
    <Link
      to={`/repositories/${repositoryId}`}
      className="text-muted-foreground hover:text-foreground inline-flex items-center gap-1 text-sm"
    >
      <ArrowLeftIcon className="size-4" aria-hidden /> {repository.data?.fullName ?? 'Repository'}
    </Link>
  )

  if (detail.isPending) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-4 w-40" />
        <Skeleton className="h-8 w-96" />
        <Skeleton className="h-32 w-full" />
      </div>
    )
  }

  if (detail.isError) {
    const notFound = detail.error instanceof ApiRequestError && detail.error.status === 404
    return (
      <EmptyState
        icon={FileSearchIcon}
        title={notFound ? 'Pull request not found' : 'Couldn’t load this pull request'}
        description={
          notFound
            ? 'Sync the repository’s pull requests if it was opened recently.'
            : 'Something went wrong. Try again in a moment.'
        }
        action={
          <Button asChild>
            <Link to={`/repositories/${repositoryId}`}>Back to the repository</Link>
          </Button>
        }
      />
    )
  }

  const pr = detail.data.pullRequest
  return (
    <div className="space-y-6">
      <div className="space-y-3">
        {backLink}
        <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
          <div className="min-w-0 space-y-2">
            <h1 className="text-2xl font-semibold tracking-tight break-words">
              {pr.title} <span className="text-muted-foreground font-normal">#{pr.number}</span>
            </h1>
            <div className="text-muted-foreground flex flex-wrap items-center gap-2 text-sm">
              <PullRequestStateBadge pullRequest={pr} />
              <AuthorAvatar login={pr.authorLogin} src={pr.authorAvatarUrl} />
              <span>
                <span className="text-foreground font-medium">{pr.authorLogin}</span> {summary(pr)}
              </span>
            </div>
            <p className="flex flex-wrap items-center gap-1 text-sm">
              <Badge variant="outline" className="font-mono">
                {pr.sourceBranch}
              </Badge>
              <span aria-label="into">→</span>
              <Badge variant="outline" className="font-mono">
                {pr.targetBranch}
              </Badge>
            </p>
          </div>
          <Button asChild variant="outline">
            <a href={pr.htmlUrl} target="_blank" rel="noreferrer">
              Open on GitHub <ExternalLinkIcon />
            </a>
          </Button>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>
            <h2>Description</h2>
          </CardTitle>
        </CardHeader>
        <CardContent>
          {detail.data.body ? (
            // Shown as plain text; Markdown rendering comes with the review report.
            <p className="text-sm break-words whitespace-pre-wrap">{detail.data.body}</p>
          ) : (
            <p className="text-muted-foreground text-sm italic">No description provided.</p>
          )}
        </CardContent>
      </Card>

      <ReviewPanel state={review} />

      <FilesChanged repositoryId={repositoryId} number={number} review={review} />
    </div>
  )
}

function summary(pr: PullRequest): string {
  if (pr.state === 'MERGED' && pr.mergedAt) return `merged ${timeAgo(pr.mergedAt)}`
  if (pr.state === 'CLOSED' && pr.closedAt) return `closed this ${timeAgo(pr.closedAt)}`
  return `opened this ${timeAgo(pr.openedAt)}`
}

function FilesChanged({
  repositoryId,
  number,
  review,
}: {
  repositoryId: number
  number: number
  review: ReviewState
}) {
  const files = usePullRequestFiles(repositoryId, number)

  return (
    <section aria-labelledby="files-heading" className="space-y-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id="files-heading" className="text-lg font-semibold">
          Files changed
        </h2>
        {files.data && <ChangeSummary files={files.data.files} />}
      </div>

      {files.isPending ? (
        <div className="space-y-3" aria-busy="true" aria-label="Loading changed files">
          <Skeleton className="h-10 w-full" />
          <Skeleton className="h-40 w-full" />
        </div>
      ) : files.isError ? (
        <Alert variant="destructive">
          <CircleAlertIcon />
          <AlertDescription className="flex flex-wrap items-center justify-between gap-2">
            {errorMessage(files.error)}
            <Button size="sm" variant="outline" onClick={() => void files.refetch()}>
              Retry
            </Button>
          </AlertDescription>
        </Alert>
      ) : (
        <>
          {files.data.truncated && (
            <Alert>
              <CircleAlertIcon />
              <AlertDescription>
                This pull request changes more files than DevPulse shows. Open it on GitHub to see
                the rest.
              </AlertDescription>
            </Alert>
          )}
          {files.data.files.length === 0 && (
            <p className="text-muted-foreground text-sm">This pull request changes no files.</p>
          )}
          {files.data.files.map((file) => (
            <FileDiff
              key={file.filename}
              file={file}
              findings={review.findings.filter((f) => f.filePath === file.filename)}
              review={review}
            />
          ))}
        </>
      )}
    </section>
  )
}

function ChangeSummary({ files }: { files: ChangedFile[] }) {
  const additions = files.reduce((sum, f) => sum + f.additions, 0)
  const deletions = files.reduce((sum, f) => sum + f.deletions, 0)
  return (
    <p className="text-muted-foreground text-sm">
      {files.length} {files.length === 1 ? 'file' : 'files'} ·{' '}
      <span className="text-emerald-700 dark:text-emerald-300">+{additions}</span>{' '}
      <span className="text-rose-700 dark:text-rose-300">−{deletions}</span>
    </p>
  )
}

/**
 * One file: a header that folds the diff open and closed (a native
 * <details>), review findings for the whole file on top, and findings for a
 * line right under that line.
 */
function FileDiff({
  file,
  findings,
  review,
}: {
  file: ChangedFile
  findings: Finding[]
  review: ReviewState
}) {
  const card = (finding: Finding) => (
    <FindingCard
      key={finding.id}
      finding={finding}
      onToggleDismissed={review.toggleDismissed}
      pending={review.dismissing}
    />
  )
  const wholeFile = findings.filter((f) => f.line == null)
  const byLine = new Map<number, React.ReactNode>()
  for (const finding of findings) {
    if (finding.line == null) continue
    const sameLine = findings.filter((f) => f.line === finding.line)
    byLine.set(finding.line, <div className="space-y-2">{sameLine.map(card)}</div>)
  }

  return (
    <Card className="gap-0 overflow-hidden py-0">
      <details
        // Big files start folded, unless the review found something in them.
        open={file.additions + file.deletions <= COLLAPSE_ABOVE || findings.length > 0}
        className="group"
      >
        <summary className="hover:bg-muted/50 flex cursor-pointer list-none items-center gap-2 px-4 py-2.5 text-sm [&::-webkit-details-marker]:hidden">
          <ChevronRightIcon
            className="text-muted-foreground size-4 shrink-0 transition-transform group-open:rotate-90"
            aria-hidden
          />
          <Badge variant="outline" className="capitalize">
            {file.status}
          </Badge>
          <span className="min-w-0 flex-1 font-mono break-all">
            {file.previousFilename && (
              <span className="text-muted-foreground">{file.previousFilename} → </span>
            )}
            {file.filename}
          </span>
          {findings.length > 0 && (
            <Badge variant="secondary" className="shrink-0">
              {findings.length} {findings.length === 1 ? 'finding' : 'findings'}
            </Badge>
          )}
          <span className="shrink-0 font-mono text-xs">
            <span className="text-emerald-700 dark:text-emerald-300">+{file.additions}</span>{' '}
            <span className="text-rose-700 dark:text-rose-300">−{file.deletions}</span>
          </span>
        </summary>
        <div className="border-t">
          {wholeFile.length > 0 && (
            <div className="bg-muted/40 space-y-2 border-b px-3 py-2">{wholeFile.map(card)}</div>
          )}
          {file.patch ? (
            <DiffView patch={file.patch} filename={file.filename} annotations={byLine} />
          ) : (
            <p className="text-muted-foreground px-4 py-3 text-sm">
              {file.status === 'renamed' && file.additions + file.deletions === 0
                ? 'File renamed without changes.'
                : 'No diff to show: a binary file, or a change too large for GitHub to display.'}
              {file.blobUrl && (
                <>
                  {' '}
                  <a
                    href={file.blobUrl}
                    target="_blank"
                    rel="noreferrer"
                    className="text-foreground underline underline-offset-4"
                  >
                    View the file on GitHub
                  </a>
                </>
              )}
            </p>
          )}
        </div>
      </details>
    </Card>
  )
}
