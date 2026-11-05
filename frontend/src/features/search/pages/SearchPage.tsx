import { DatabaseIcon, ExternalLinkIcon, SearchIcon, SearchXIcon } from 'lucide-react'
import { type FormEvent, useState } from 'react'
import { Link, useSearchParams } from 'react-router'

import { EmptyState } from '@/components/common/EmptyState'
import { PageHeader } from '@/components/common/PageHeader'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { useRepositories } from '@/features/repositories/hooks'
import { errorMessage } from '@/lib/api/errors'

import { type SearchResult, useCodeSearch } from '../api'

const EXAMPLES = [
  'where are JWT tokens validated',
  'how are passwords hashed',
  'retry a failed request',
]

/**
 * Search by meaning: the query is embedded with the same model as the code
 * and compared with every indexed chunk. The query and the repository live
 * in the URL (?q=&repo=), so a search can be bookmarked or shared.
 */
export function SearchPage() {
  const [params, setParams] = useSearchParams()
  const query = params.get('q') ?? ''
  const repositoryId = params.get('repo') ? Number(params.get('repo')) : undefined
  const [draft, setDraft] = useState(query)

  const repositories = useRepositories({ q: '', filter: 'all', sort: 'NAME', page: 0 }, true)
  const indexed = (repositories.data?.content ?? []).filter((r) => r.indexStatus === 'SUCCEEDED')
  const results = useCodeSearch(query, repositoryId)

  const go = (q: string, repo = repositoryId) => {
    const next = new URLSearchParams()
    if (q.trim()) next.set('q', q.trim())
    if (repo) next.set('repo', String(repo))
    setParams(next)
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    go(draft)
  }

  return (
    <>
      <PageHeader
        title="Code search"
        description="Describe what you're looking for in plain words. DevPulse finds code that does it, even if it uses different words."
      />

      <form onSubmit={submit} className="mb-4 flex flex-col gap-3 md:flex-row md:items-center">
        <div className="relative flex-1">
          <SearchIcon
            className="text-muted-foreground pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2"
            aria-hidden
          />
          <Input
            type="search"
            aria-label="Search code"
            placeholder="e.g. where are JWT tokens validated"
            className="pl-9"
            maxLength={500}
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
          />
        </div>
        <select
          aria-label="Repository"
          className="border-input bg-background h-9 rounded-md border px-2 text-sm"
          value={repositoryId ?? ''}
          onChange={(e) => go(draft, e.target.value ? Number(e.target.value) : undefined)}
        >
          <option value="">All indexed repositories</option>
          {indexed.map((r) => (
            <option key={r.id} value={r.id}>
              {r.fullName}
            </option>
          ))}
        </select>
        <Button type="submit" disabled={!draft.trim()}>
          Search
        </Button>
      </form>

      {repositories.data && indexed.length === 0 ? (
        <EmptyState
          icon={DatabaseIcon}
          title="Nothing indexed yet"
          description="Open a repository and press Index code: its default branch is embedded locally, for free."
          action={
            <Button asChild>
              <Link to="/repositories">Go to repositories</Link>
            </Button>
          }
        />
      ) : !query ? (
        <div className="space-y-2">
          <p className="text-muted-foreground text-sm">Try:</p>
          <div className="flex flex-wrap gap-2">
            {EXAMPLES.map((example) => (
              <Button
                key={example}
                variant="outline"
                size="sm"
                onClick={() => {
                  setDraft(example)
                  go(example)
                }}
              >
                {example}
              </Button>
            ))}
          </div>
        </div>
      ) : results.isPending ? (
        <div className="space-y-3" aria-busy="true" aria-label="Searching">
          <Skeleton className="h-28 w-full" />
          <Skeleton className="h-28 w-full" />
        </div>
      ) : results.isError ? (
        <Alert variant="destructive">
          <AlertDescription>{errorMessage(results.error)}</AlertDescription>
        </Alert>
      ) : results.data.length === 0 ? (
        <EmptyState
          icon={SearchXIcon}
          title="No matches"
          description="Nothing indexed is close to that. Try other words, or index more repositories."
        />
      ) : (
        <ol className="space-y-3" aria-label="Results">
          {results.data.map((result, i) => (
            <li key={`${result.repositoryId}:${result.path}:${result.startLine}`}>
              <ResultCard result={result} rank={i + 1} />
            </li>
          ))}
        </ol>
      )}
    </>
  )
}

function ResultCard({ result, rank }: { result: SearchResult; rank: number }) {
  const lines = result.content.split('\n')
  return (
    <Card className="gap-0 overflow-hidden py-0">
      <div className="flex flex-wrap items-center gap-2 border-b px-4 py-2.5 text-sm">
        <span className="text-muted-foreground tabular-nums">{rank}.</span>
        <span className="min-w-0 font-mono break-all">
          {result.path}
          <span className="text-muted-foreground">
            :{result.startLine}–{result.endLine}
          </span>
        </span>
        <span className="text-muted-foreground text-xs">{result.repositoryFullName}</span>
        <Badge
          variant="secondary"
          className="ml-auto"
          title="How close in meaning (cosine similarity)"
        >
          {Math.round(result.score * 100)}% match
        </Badge>
        {result.url && (
          <a
            href={result.url}
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-1 text-xs underline underline-offset-4"
          >
            GitHub <ExternalLinkIcon className="size-3" />
          </a>
        )}
      </div>
      <pre className="overflow-x-auto py-2 font-mono text-xs leading-5">
        {lines.map((line, i) => (
          <div key={i} className="flex">
            <span className="text-muted-foreground w-12 shrink-0 pr-3 text-right select-none">
              {result.startLine + i}
            </span>
            <code className="whitespace-pre">{line}</code>
          </div>
        ))}
      </pre>
    </Card>
  )
}
