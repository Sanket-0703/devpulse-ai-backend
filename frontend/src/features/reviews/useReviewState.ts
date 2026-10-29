import { useState } from 'react'

import { type Finding, type Severity } from './api'
import { useDismissFinding, usePullRequestReviews, useReview, useStartReview } from './hooks'

export type SeverityFilter = 'all' | Severity

/**
 * Everything the pull request page needs about reviews: the history, the
 * review being looked at (the newest unless the user picks another), the
 * filters, and the findings that pass them (shown in the panel and on the diff).
 */
export function useReviewState(repositoryId: number, number: number) {
  const reviews = usePullRequestReviews(repositoryId, number)
  const [pickedId, setPickedId] = useState<number>()
  const selectedId = pickedId ?? reviews.data?.[0]?.id
  const detail = useReview(selectedId)
  const start = useStartReview(repositoryId, number)
  const dismiss = useDismissFinding(selectedId ?? 0)
  const [severity, setSeverity] = useState<SeverityFilter>('all')
  const [showDismissed, setShowDismissed] = useState(false)

  const findings = (detail.data?.findings ?? []).filter(
    (f) => (severity === 'all' || f.severity === severity) && (showDismissed || !f.dismissed),
  )

  return {
    reviews,
    selectedId,
    select: setPickedId,
    detail,
    findings,
    severity,
    setSeverity,
    showDismissed,
    setShowDismissed,
    run: () => start.mutate(undefined, { onSuccess: () => setPickedId(undefined) }), // show the new one
    starting: start.isPending,
    toggleDismissed: (finding: Finding) =>
      dismiss.mutate({ findingId: finding.id, dismissed: !finding.dismissed }),
    dismissing: dismiss.isPending,
  }
}

export type ReviewState = ReturnType<typeof useReviewState>
