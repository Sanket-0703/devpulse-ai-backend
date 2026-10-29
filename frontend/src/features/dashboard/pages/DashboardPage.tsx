import { LayoutDashboardIcon } from 'lucide-react'
import { Link } from 'react-router'

import { EmptyState } from '@/components/common/EmptyState'
import { PageHeader } from '@/components/common/PageHeader'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { formatCost } from '@/features/reviews/api'
import { FindingCountBadges, ReviewStatusBadge } from '@/features/reviews/components'
import { UsageCards } from '@/features/reviews/UsageCards'
import { timeAgo } from '@/lib/time'

import { type Dashboard, useDashboard } from '../api'
import { OnboardingChecklist, SeverityChart, StatCards } from '../components'

export function DashboardPage() {
  const dashboard = useDashboard()

  return (
    <>
      <PageHeader
        title="Dashboard"
        description="Your repositories, open findings, recent reviews and AI usage at a glance."
      />

      {dashboard.isPending ? (
        <div className="space-y-4" aria-busy="true" aria-label="Loading the dashboard">
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-48 w-full" />
        </div>
      ) : dashboard.isError ? (
        <EmptyState
          icon={LayoutDashboardIcon}
          title="Couldn't load the dashboard"
          description="Something went wrong. Try again in a moment."
          action={<Button onClick={() => void dashboard.refetch()}>Retry</Button>}
        />
      ) : (
        <DashboardContent data={dashboard.data} />
      )}
    </>
  )
}

function DashboardContent({ data }: { data: Dashboard }) {
  const allDone = Object.values(data.onboarding).every(Boolean)
  return (
    <div className="space-y-6">
      {!allDone && <OnboardingChecklist onboarding={data.onboarding} />}
      <StatCards stats={data.stats} />
      <div className="grid gap-6 lg:grid-cols-2">
        <SeverityChart counts={data.openFindings} />
        <RecentReviews reviews={data.recentReviews} />
      </div>
      <section aria-labelledby="usage-heading" className="space-y-3">
        <h2 id="usage-heading" className="text-lg font-semibold">
          AI usage
        </h2>
        <UsageCards usage={data.usage} />
      </section>
    </div>
  )
}

function RecentReviews({ reviews }: { reviews: Dashboard['recentReviews'] }) {
  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle>
          <h2>Recent reviews</h2>
        </CardTitle>
        {reviews.length > 0 && (
          <Link to="/reviews" className="text-sm underline-offset-4 hover:underline">
            All reviews
          </Link>
        )}
      </CardHeader>
      <CardContent>
        {reviews.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            No reviews yet. Open a pull request and press Run AI review.
          </p>
        ) : (
          <ul className="divide-y">
            {reviews.map((review) => (
              <li key={review.id} className="flex flex-wrap items-center gap-2 py-2.5 first:pt-0">
                <div className="min-w-0 flex-1">
                  <Link
                    to={`/repositories/${review.repositoryId}/pulls/${review.pullRequestNumber}`}
                    className="text-sm font-medium break-words underline-offset-4 hover:underline"
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
        )}
      </CardContent>
    </Card>
  )
}
