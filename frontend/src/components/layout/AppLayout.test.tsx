import { screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'

import { API_BASE_URL } from '@/lib/api/client'
import { server } from '@/test/server'
import { renderApp } from '@/test/render'

describe('App shell', () => {
  it('redirects / to the dashboard', async () => {
    const { router } = renderApp('/')

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/dashboard')
  })

  it('navigates between pages from the sidebar', async () => {
    const { user } = renderApp('/dashboard')
    const nav = await screen.findByRole('navigation', { name: 'Main' })

    await user.click(within(nav).getByRole('link', { name: 'Code search' }))

    expect(await screen.findByRole('heading', { name: 'Code search' })).toBeInTheDocument()
    expect(screen.getByText('This page arrives in Level 10 of the build plan.')).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: 'Code search' })).toHaveAttribute(
      'aria-current',
      'page',
    )
  })

  it('shows a 404 page for unknown routes', async () => {
    renderApp('/does-not-exist')

    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to dashboard' })).toHaveAttribute(
      'href',
      '/dashboard',
    )
  })

  it('switches to dark mode and remembers the choice', async () => {
    const { user } = renderApp('/dashboard')

    await user.click(await screen.findByRole('button', { name: 'Change theme' }))
    await user.click(await screen.findByRole('menuitemradio', { name: 'Dark' }))

    expect(document.documentElement).toHaveClass('dark')
    expect(localStorage.getItem('devpulse-theme')).toBe('dark')
  })

  it('shows the API as online when the backend is healthy', async () => {
    renderApp('/dashboard')

    expect(await screen.findByText('API online')).toBeInTheDocument()
  })

  it('shows the API as offline when the backend is unreachable', async () => {
    server.use(http.get(`${API_BASE_URL}/actuator/health`, () => HttpResponse.error()))

    renderApp('/dashboard')

    expect(await screen.findByText('API offline')).toBeInTheDocument()
  })
})
