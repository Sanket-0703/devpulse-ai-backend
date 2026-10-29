import { ShieldCheckIcon } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'

import { EmptyState } from '@/components/common/EmptyState'
import { PageHeader } from '@/components/common/PageHeader'
import { Pagination } from '@/components/common/Pagination'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { timeAgo } from '@/lib/time'
import { cn } from '@/lib/utils'

import { formatCost } from '../api'
import { FindingCountBadges, ReviewStatusBadge } from '../components'
import { useReviews, useUsage } from '../hooks'
import { UsageCards } from '../UsageCards'

/** Every review you ran, newest first, with today's usage against your limits. */
export function ReviewsPage() {
  const [page, setPage] = useState(0)
  const reviews = useReviews(page)
  const usage = useUsage()

  return (
    <>
      <PageHeader
        title="Reviews"
        description="Every AI review you ran, with what it found and what it cost."
      />

      {usage.data && <UsageCards usage={usage.data} />}

      {reviews.isPending ? (
        <Skeleton className="h-40 w-full" />
      ) : reviews.isError ? (
        <EmptyState
          icon={ShieldCheckIcon}
          title="Couldn't load reviews"
          description="Something went wrong. Try again in a moment."
        />
      ) : reviews.data.totalElements === 0 ? (
        <EmptyState
          icon={ShieldCheckIcon}
          title="No reviews yet"
          description="Open a pull request from one of your repositories and press Run AI review."
        />
      ) : (
        <>
          <Card className={cn('gap-0 py-0', reviews.isPlaceholderData && 'opacity-60')}>
            <ul className="divide-y">
              {reviews.data.content.map((review) => (
                <li key={review.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
                  <div className="min-w-0 flex-1 space-y-1">
                    <Link
                      to={`/repositories/${review.repositoryId}/pulls/${review.pullRequestNumber}`}
                      className="font-medium break-words underline-offset-4 hover:underline"
                    >
                      {review.pullRequestTitle}
                    </Link>
                    <p className="text-muted-foreground text-xs">
                      {review.repositoryFullName} #{review.pullRequestNumber} ·{' '}
                      {timeAgo(review.createdAt)} · {formatCost(review)}
                    </p>
                  </div>
                  {review.status === 'SUCCEEDED' ? (
                    <FindingCountBadges counts={review.openFindings} />
                  ) : (
                    <ReviewStatusBadge review={review} />
                  )}
                </li>
              ))}
            </ul>
          </Card>
          <Pagination
            page={reviews.data.page}
            totalPages={reviews.data.totalPages}
            totalElements={reviews.data.totalElements}
            noun="reviews"
            onPage={setPage}
          />
        </>
      )}
    </>
  )
}
