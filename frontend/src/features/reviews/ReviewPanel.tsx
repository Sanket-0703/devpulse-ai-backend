import { CircleAlertIcon, Loader2Icon, SparklesIcon } from 'lucide-react'

import { SegmentedControl } from '@/components/common/SegmentedControl'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { timeAgo } from '@/lib/time'

import { formatCost, isActive } from './api'
import { FindingCountBadges, ReviewStatusBadge, SeverityBadge } from './components'
import type { ReviewState, SeverityFilter } from './useReviewState'

const FILTERS: { value: SeverityFilter; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'CRITICAL', label: 'Critical' },
  { value: 'HIGH', label: 'High' },
  { value: 'MEDIUM', label: 'Medium' },
  { value: 'LOW', label: 'Low' },
]

export function ReviewPanel({ state }: { state: ReviewState }) {
  const history = state.reviews.data ?? []
  const review = state.detail.data?.review
  const running = state.starting || (review !== undefined && isActive(review))
  const dismissedCount = state.detail.data?.findings.filter((f) => f.dismissed).length ?? 0

  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-start justify-between gap-3">
        <div className="space-y-1.5">
          <CardTitle>
            <h2>AI review</h2>
          </CardTitle>
          <CardDescription>
            DevPulse checks the changed lines with its security rules and the AI model.
          </CardDescription>
        </div>
        <Button onClick={state.run} disabled={running}>
          {running ? <Loader2Icon className="animate-spin" /> : <SparklesIcon />}
          {history.length === 0 ? 'Run AI review' : 'Review again'}
        </Button>
      </CardHeader>

      <CardContent className="space-y-4">
        {state.reviews.isPending ? null : history.length === 0 ? (
          <p className="text-muted-foreground text-sm">No review yet.</p>
        ) : (
          <>
            <div className="flex flex-wrap items-center gap-3 text-sm">
              {review && <ReviewStatusBadge review={review} />}
              {history.length > 1 && (
                <label className="flex items-center gap-2">
                  <span className="text-muted-foreground">Review</span>
                  <select
                    className="border-input bg-background rounded-md border px-2 py-1 text-sm"
                    value={state.selectedId}
                    onChange={(e) => state.select(Number(e.target.value))}
                  >
                    {history.map((r, i) => (
                      <option key={r.id} value={r.id}>
                        {i === 0 ? 'Latest' : `#${history.length - i}`} · {timeAgo(r.createdAt)} ·{' '}
                        {r.headSha.slice(0, 7)}
                      </option>
                    ))}
                  </select>
                </label>
              )}
            </div>

            {review && isActive(review) && (
              <p className="text-muted-foreground text-sm" aria-live="polite">
                Reviewing the changes… this page updates by itself.
              </p>
            )}

            {review?.status === 'FAILED' && (
              <Alert variant="destructive">
                <CircleAlertIcon />
                <AlertDescription>{review.error}</AlertDescription>
              </Alert>
            )}

            {review?.status === 'SUCCEEDED' && (
              <>
                <div className="space-y-1">
                  <FindingCountBadges counts={review.openFindings} />
                  <p className="text-muted-foreground text-xs">
                    {review.filesReviewed} {review.filesReviewed === 1 ? 'file' : 'files'} reviewed
                    {review.filesSkipped > 0 && ` · ${review.filesSkipped} skipped`} ·{' '}
                    {review.chunks} AI {review.chunks === 1 ? 'request' : 'requests'} ·{' '}
                    {(review.promptTokens + review.completionTokens).toLocaleString()} tokens ·{' '}
                    {formatCost(review)} · commit {review.headSha.slice(0, 7)} ·{' '}
                    {timeAgo(review.finishedAt ?? review.createdAt)}
                  </p>
                </div>

                {review.truncated && (
                  <Alert>
                    <CircleAlertIcon />
                    <AlertDescription>
                      This pull request is large: part of it wasn’t reviewed (size or cost limit).
                    </AlertDescription>
                  </Alert>
                )}

                <div className="flex flex-wrap items-center gap-3">
                  <SegmentedControl
                    label="Severity"
                    options={FILTERS}
                    value={state.severity}
                    onChange={state.setSeverity}
                  />
                  {dismissedCount > 0 && (
                    <label className="flex items-center gap-2 text-sm">
                      <input
                        type="checkbox"
                        checked={state.showDismissed}
                        onChange={(e) => state.setShowDismissed(e.target.checked)}
                      />
                      Show dismissed ({dismissedCount})
                    </label>
                  )}
                </div>

                {state.findings.length === 0 ? (
                  <p className="text-muted-foreground text-sm">No findings to show.</p>
                ) : (
                  // A compact index; the full cards sit on the diff below, next to their lines.
                  <ul className="divide-y rounded-md border" aria-label="Findings">
                    {state.findings.map((finding) => (
                      <li key={finding.id}>
                        <a
                          href={`#finding-${finding.id}`}
                          className="hover:bg-muted/50 flex flex-wrap items-center gap-2 px-3 py-2 text-sm"
                        >
                          <SeverityBadge severity={finding.severity} />
                          <span className={finding.dismissed ? 'line-through' : undefined}>
                            {finding.title}
                          </span>
                          <span className="text-muted-foreground ml-auto font-mono text-xs break-all">
                            {finding.filePath}
                            {finding.line != null && `:${finding.line}`}
                          </span>
                        </a>
                      </li>
                    ))}
                  </ul>
                )}
              </>
            )}
          </>
        )}
      </CardContent>
    </Card>
  )
}
