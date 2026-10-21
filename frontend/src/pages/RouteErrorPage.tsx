import { TriangleAlertIcon } from 'lucide-react'
import { isRouteErrorResponse, useRouteError } from 'react-router'

import { EmptyState } from '@/components/common/EmptyState'
import { Button } from '@/components/ui/button'

/** Error boundary for the router: shown when a page throws while rendering. */
export function RouteErrorPage() {
  const error = useRouteError()

  const message = isRouteErrorResponse(error)
    ? `${error.status} ${error.statusText}`
    : error instanceof Error
      ? error.message
      : 'Unknown error'

  return (
    <div className="p-4 md:p-8">
      <EmptyState
        icon={TriangleAlertIcon}
        title="Something went wrong"
        description={message}
        action={<Button onClick={() => window.location.reload()}>Reload page</Button>}
      />
    </div>
  )
}
