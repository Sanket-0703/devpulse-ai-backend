import { Navigate, type RouteObject } from 'react-router'

import { AppLayout } from '@/components/layout/AppLayout'
import { AuthLayout } from '@/components/layout/AuthLayout'
import { NAV_ITEMS } from '@/components/layout/navigation'
import { LoginPage } from '@/features/auth/pages/LoginPage'
import { RegisterPage } from '@/features/auth/pages/RegisterPage'
import { GuestOnly, RequireAuth } from '@/features/auth/routeGuards'
import { ComingSoonPage } from '@/pages/ComingSoonPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { RouteErrorPage } from '@/pages/RouteErrorPage'

export const routes: RouteObject[] = [
  {
    errorElement: <RouteErrorPage />,
    children: [
      // Public: only for signed-out users.
      {
        element: <GuestOnly />,
        children: [
          {
            element: <AuthLayout />,
            children: [
              { path: '/login', element: <LoginPage /> },
              { path: '/register', element: <RegisterPage /> },
            ],
          },
        ],
      },
      // Everything else needs a signed-in user.
      {
        element: <RequireAuth />,
        children: [
          {
            path: '/',
            element: <AppLayout />,
            children: [
              { index: true, element: <Navigate to="/dashboard" replace /> },
              // Each level swaps a ComingSoonPage for the real page.
              ...NAV_ITEMS.map((item) => ({
                path: item.to,
                element: <ComingSoonPage item={item} />,
              })),
              { path: '*', element: <NotFoundPage /> },
            ],
          },
        ],
      },
    ],
  },
]
