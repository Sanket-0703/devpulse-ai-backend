import { Navigate, type RouteObject } from 'react-router'

import { AppLayout } from '@/components/layout/AppLayout'
import { AuthLayout } from '@/components/layout/AuthLayout'
import { NAV_ITEMS } from '@/components/layout/navigation'
import { LoginPage } from '@/features/auth/pages/LoginPage'
import { RegisterPage } from '@/features/auth/pages/RegisterPage'
import { GuestOnly, RequireAuth } from '@/features/auth/routeGuards'
import { GitHubCallbackPage } from '@/features/github/GitHubCallbackPage'
import { RepositoriesPage } from '@/features/repositories/pages/RepositoriesPage'
import { RepositoryDetailPage } from '@/features/repositories/pages/RepositoryDetailPage'
import { GitHubSettingsPage } from '@/features/settings/pages/GitHubSettingsPage'
import { ProfileSettingsPage } from '@/features/settings/pages/ProfileSettingsPage'
import { SecuritySettingsPage } from '@/features/settings/pages/SecuritySettingsPage'
import { SettingsLayout } from '@/features/settings/SettingsLayout'
import { ComingSoonPage } from '@/pages/ComingSoonPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { RouteErrorPage } from '@/pages/RouteErrorPage'

// Pages already built; every other nav item still shows a ComingSoonPage.
const BUILT_PAGES = new Set(['/settings', '/repositories'])

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
      // GitHub sends the browser back here, signed in or not (outside the guards).
      { path: '/github/callback', element: <GitHubCallbackPage /> },
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
              ...NAV_ITEMS.filter((item) => !BUILT_PAGES.has(item.to)).map((item) => ({
                path: item.to,
                element: <ComingSoonPage item={item} />,
              })),
              { path: '/repositories', element: <RepositoriesPage /> },
              { path: '/repositories/:id', element: <RepositoryDetailPage /> },
              {
                path: '/settings',
                element: <SettingsLayout />,
                children: [
                  { index: true, element: <Navigate to="profile" replace /> },
                  { path: 'profile', element: <ProfileSettingsPage /> },
                  { path: 'security', element: <SecuritySettingsPage /> },
                  { path: 'github', element: <GitHubSettingsPage /> },
                ],
              },
              { path: '*', element: <NotFoundPage /> },
            ],
          },
        ],
      },
    ],
  },
]
