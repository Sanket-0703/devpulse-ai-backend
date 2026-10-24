import { EmptyState } from '@/components/common/EmptyState'
import { PageHeader } from '@/components/common/PageHeader'
import type { NavItem } from '@/components/layout/navigation'
import { Badge } from '@/components/ui/badge'

/** Placeholder for a page that a later build-plan level delivers. */
export function ComingSoonPage({ item }: { item: NavItem }) {
  return (
    <>
      <PageHeader title={item.label} description={item.description} />
      <EmptyState
        icon={item.icon}
        title="Not built yet"
        description={`This page arrives in Level ${item.level} of the build plan.`}
        action={<Badge variant="secondary">Level {item.level}</Badge>}
      />
    </>
  )
}
