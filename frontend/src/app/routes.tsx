import { Navigate, type RouteObject } from 'react-router'

import { AppLayout } from '@/components/layout/AppLayout'
import { NAV_ITEMS } from '@/components/layout/navigation'
import { ComingSoonPage } from '@/pages/ComingSoonPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { RouteErrorPage } from '@/pages/RouteErrorPage'

// Each level swaps a ComingSoonPage for the real page.
export const routes: RouteObject[] = [
  {
    path: '/',
    element: <AppLayout />,
    errorElement: <RouteErrorPage />,
    children: [
      { index: true, element: <Navigate to="/dashboard" replace /> },
      ...NAV_ITEMS.map((item) => ({
        path: item.to,
        element: <ComingSoonPage item={item} />,
      })),
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]
