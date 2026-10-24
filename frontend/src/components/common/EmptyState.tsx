import type { LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'

type EmptyStateProps = {
  icon: LucideIcon
  title: string
  description: string
  action?: ReactNode
}

/** Shown when a list or page has nothing to display yet. */
export function EmptyState({ icon: Icon, title, description, action }: EmptyStateProps) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 rounded-lg border border-dashed px-6 py-16 text-center">
      <span className="bg-muted flex size-12 items-center justify-center rounded-full">
        <Icon className="text-muted-foreground size-6" aria-hidden />
      </span>
      <h2 className="text-lg font-medium">{title}</h2>
      <p className="text-muted-foreground max-w-md text-sm">{description}</p>
      {action}
    </div>
  )
}
