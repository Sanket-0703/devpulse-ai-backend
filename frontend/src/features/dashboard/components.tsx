import { CircleCheckIcon, CircleIcon } from 'lucide-react'
import { Link } from 'react-router'

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import type { FindingCounts } from '@/features/reviews/api'
import { cn } from '@/lib/utils'

import type { Dashboard, Onboarding } from './api'

const STEPS: { key: keyof Onboarding; label: string; to: string }[] = [
  { key: 'githubConnected', label: 'Connect your GitHub account', to: '/settings/github' },
  {
    key: 'appInstalled',
    label: 'Install the DevPulse app on some repositories',
    to: '/settings/github',
  },
  { key: 'repositoriesSynced', label: 'Sync your repositories', to: '/repositories' },
  { key: 'reviewEnabled', label: 'Turn on AI review for a repository', to: '/repositories' },
  {
    key: 'firstReviewDone',
    label: 'Run your first AI review on a pull request',
    to: '/repositories',
  },
]

/** The getting-started steps; the caller hides it once everything is done. */
export function OnboardingChecklist({ onboarding }: { onboarding: Onboarding }) {
  const done = STEPS.filter((step) => onboarding[step.key]).length
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2>Get started</h2>
        </CardTitle>
        <CardDescription>
          {done} of {STEPS.length} steps done
        </CardDescription>
      </CardHeader>
      <CardContent>
        <ol className="space-y-2">
          {STEPS.map((step) => {
            const complete = onboarding[step.key]
            return (
              <li key={step.key} className="flex items-center gap-2 text-sm">
                {complete ? (
                  <CircleCheckIcon className="size-4 text-emerald-600" aria-hidden />
                ) : (
                  <CircleIcon className="text-muted-foreground size-4" aria-hidden />
                )}
                <span className="sr-only">{complete ? 'Done: ' : 'To do: '}</span>
                {complete ? (
                  <span className="text-muted-foreground line-through">{step.label}</span>
                ) : (
                  <Link to={step.to} className="font-medium underline-offset-4 hover:underline">
                    {step.label}
                  </Link>
                )}
              </li>
            )
          })}
        </ol>
      </CardContent>
    </Card>
  )
}

export function StatCards({ stats }: { stats: Dashboard['stats'] }) {
  const items = [
    { label: 'Repositories', value: stats.repositories },
    { label: 'AI review on', value: stats.reviewEnabledRepositories },
    { label: 'Open pull requests', value: stats.openPullRequests },
    { label: 'Reviews (30 days)', value: stats.reviewsLast30Days },
  ]
  return (
    <section aria-label="Totals" className="grid grid-cols-2 gap-3 lg:grid-cols-4">
      {items.map((item) => (
        <Card key={item.label} className="py-4">
          <CardContent className="space-y-1 px-4">
            <p className="text-muted-foreground text-xs">{item.label}</p>
            <p className="text-2xl font-semibold tabular-nums">{item.value}</p>
          </CardContent>
        </Card>
      ))}
    </section>
  )
}

const BARS: { key: keyof FindingCounts; label: string; color: string }[] = [
  { key: 'critical', label: 'Critical', color: 'bg-red-600 dark:bg-red-500' },
  { key: 'high', label: 'High', color: 'bg-orange-500' },
  { key: 'medium', label: 'Medium', color: 'bg-amber-400' },
  { key: 'low', label: 'Low', color: 'bg-sky-500' },
]

/**
 * Open findings per severity as horizontal bars, scaled to the largest.
 * The numbers are always printed, so the chart doesn't rely on colour or length.
 */
export function SeverityChart({ counts }: { counts: FindingCounts }) {
  const max = Math.max(1, ...BARS.map((bar) => counts[bar.key]))
  const total = BARS.reduce((sum, bar) => sum + counts[bar.key], 0)
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2>Open findings</h2>
        </CardTitle>
        <CardDescription>From each pull request&apos;s latest review</CardDescription>
      </CardHeader>
      <CardContent>
        {total === 0 ? (
          <p className="text-muted-foreground text-sm">No open findings.</p>
        ) : (
          <dl className="space-y-3" aria-label="Open findings by severity">
            {BARS.map((bar) => (
              <div
                key={bar.key}
                className="grid grid-cols-[4.5rem_1fr_2rem] items-center gap-3 text-sm"
              >
                <dt>{bar.label}</dt>
                <div className="bg-muted h-3 overflow-hidden rounded-full" aria-hidden>
                  <div
                    className={cn('h-full rounded-full', bar.color)}
                    style={{ width: `${(counts[bar.key] / max) * 100}%` }}
                  />
                </div>
                <dd className="text-right font-medium tabular-nums">{counts[bar.key]}</dd>
              </div>
            ))}
          </dl>
        )}
      </CardContent>
    </Card>
  )
}
