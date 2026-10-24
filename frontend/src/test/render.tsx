import { render } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router'

import { AppProviders } from '@/app/AppProviders'
import { routes } from '@/app/routes'

/** Renders the real app routes at `path`, with all providers, in memory. */
export function renderApp(path = '/') {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const user = userEvent.setup()

  const view = render(
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>,
  )

  return { ...view, router, user }
}
