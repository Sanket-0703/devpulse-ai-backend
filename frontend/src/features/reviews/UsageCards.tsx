import { Card, CardContent } from '@/components/ui/card'

import type { AIUsage } from './api'

/**
 * Today's AI usage against the daily limits, and this month's totals.
 * Costs and tokens cover reviews and Playground runs together.
 */
export function UsageCards({ usage }: { usage: AIUsage }) {
  const usd = (value: number) => `$${value.toFixed(value < 1 ? 4 : 2)}`
  const items = [
    {
      label: 'Reviews today',
      value: `${usage.reviewsToday} of ${usage.dailyReviewLimit}`,
    },
    {
      label: 'Cost today',
      value: `${usd(usage.costTodayUsd)} of ${usd(usage.dailyCostLimitUsd)}`,
    },
    {
      label: 'Playground today',
      value: `${usage.playgroundRunsToday} of ${usage.dailyPlaygroundLimit} runs`,
    },
    {
      label: 'This month',
      value: `${usage.reviewsThisMonth} reviews · ${usage.tokensThisMonth.toLocaleString()} tokens`,
    },
    {
      label: 'Model',
      value: usage.provider === 'mock' ? 'Mock AI (free)' : `${usage.provider} · ${usage.model}`,
    },
  ]
  return (
    <section aria-label="AI usage" className="mb-6 grid grid-cols-2 gap-3 lg:grid-cols-5">
      {items.map((item) => (
        <Card key={item.label} className="py-4">
          <CardContent className="space-y-1 px-4">
            <p className="text-muted-foreground text-xs">{item.label}</p>
            <p className="text-sm font-semibold">{item.value}</p>
          </CardContent>
        </Card>
      ))}
    </section>
  )
}
