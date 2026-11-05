import { CircleAlertIcon, DatabaseIcon, Loader2Icon, RefreshCwIcon, SearchIcon } from 'lucide-react'
import { Link } from 'react-router'

import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { timeAgo } from '@/lib/time'

import type { Repository } from './api'
import { useIndexRepository } from './hooks'

/**
 * The repository's semantic code index: what's indexed, and a button to
 * (re-)index the default branch. Used by Code search and to give AI reviews
 * related code as context.
 */
export function CodeIndexCard({ repository }: { repository: Repository }) {
  const index = useIndexRepository(repository.id)
  const running = repository.indexStatus === 'RUNNING' || index.isPending
  const indexed = repository.indexStatus === 'SUCCEEDED'

  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-start justify-between gap-3">
        <div className="space-y-1.5">
          <CardTitle>
            <h2>Code index</h2>
          </CardTitle>
          <CardDescription>
            The {repository.defaultBranch} branch, split into pieces and embedded locally, so you
            can search it by meaning and reviews see related code.
          </CardDescription>
        </div>
        <Button variant="outline" onClick={() => index.mutate()} disabled={running}>
          {running ? <Loader2Icon className="animate-spin" /> : <RefreshCwIcon />}
          {repository.indexStatus === 'NEVER' ? 'Index code' : 'Re-index'}
        </Button>
      </CardHeader>
      <CardContent className="space-y-3 text-sm">
        <p className="flex items-center gap-2" aria-live="polite">
          <DatabaseIcon className="text-muted-foreground size-4" aria-hidden />
          {running
            ? 'Indexing… this page updates by itself.'
            : indexed
              ? `${repository.indexedFiles} files · ${repository.indexedChunks} chunks · indexed ${timeAgo(repository.indexedAt!)}${
                  repository.indexedCommitSha && repository.indexedCommitSha.length >= 7
                    ? ` · commit ${repository.indexedCommitSha.slice(0, 7)}`
                    : ''
                }`
              : 'Not indexed yet.'}
        </p>
        {repository.indexStatus === 'FAILED' && repository.indexError && (
          <Alert variant="destructive">
            <CircleAlertIcon />
            <AlertDescription>{repository.indexError}</AlertDescription>
          </Alert>
        )}
        {indexed && (
          <Link
            to={`/search?repo=${repository.id}`}
            className="inline-flex items-center gap-1 underline underline-offset-4"
          >
            <SearchIcon className="size-4" aria-hidden /> Search this repository
          </Link>
        )}
      </CardContent>
    </Card>
  )
}
