import { screen, waitFor, within } from '@testing-library/react'

import { redirectTo } from '@/lib/browser'
import {
  GITHUB_AUTHORIZE_URL,
  GITHUB_INSTALL_URL,
  resetAuthMock,
  setDemoGitHub,
} from '@/test/authMock'
import { renderApp } from '@/test/render'

// jsdom can't navigate to github.com; record where the app tried to go instead.
vi.mock('@/lib/browser', () => ({ redirectTo: vi.fn() }))

afterEach(() => vi.mocked(redirectTo).mockClear())

describe('Sign in with GitHub', () => {
  beforeEach(() => resetAuthMock({ signedIn: false }))

  it('is offered on the sign-in and sign-up pages as a link to the backend', async () => {
    const { user } = renderApp('/login')

    const link = await screen.findByRole('link', { name: 'Continue with GitHub' })
    expect(link).toHaveAttribute('href', 'http://localhost:8080/api/auth/github')

    await user.click(screen.getByRole('link', { name: 'Create an account' }))
    expect(await screen.findByRole('link', { name: 'Continue with GitHub' })).toBeInTheDocument()
  })
})

describe('GitHub callback page', () => {
  it('signed in → goes to the dashboard with a welcome', async () => {
    // The backend set the refresh cookie before redirecting, so the session restores.
    const { router } = renderApp('/github/callback?result=signed-in&new=true')

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/dashboard')
    expect(await screen.findByText('Welcome to DevPulse!')).toBeInTheDocument()
  })

  it('connected → back to Settings → GitHub', async () => {
    setDemoGitHub({ username: 'octo-ada', installed: true })
    const { router } = renderApp('/github/callback?result=connected')

    expect(await screen.findByText('GitHub connected')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/settings/github')
  })

  it('explains an error and offers the way back to sign in', async () => {
    resetAuthMock({ signedIn: false })
    renderApp('/github/callback?result=error&reason=email_exists')

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'A DevPulse account with your GitHub email already exists',
    )
    expect(screen.getByRole('link', { name: 'Back to sign in' })).toHaveAttribute('href', '/login')
  })

  it('offers the way back to settings when already signed in', async () => {
    renderApp('/github/callback?result=error&reason=github_account_in_use')

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'already connected to another DevPulse account',
    )
    expect(screen.getByRole('link', { name: 'Back to settings' })).toHaveAttribute(
      'href',
      '/settings/github',
    )
  })

  it('falls back to a generic message for unknown reasons', async () => {
    renderApp('/github/callback?result=error&reason=something_new')

    expect(await screen.findByRole('alert')).toHaveTextContent('GitHub didn’t respond as expected')
  })
})

describe('Settings → GitHub', () => {
  it('not connected: Connect GitHub sends the browser to GitHub', async () => {
    const { user } = renderApp('/settings/github')

    await user.click(await screen.findByRole('button', { name: 'Connect GitHub' }))

    await waitFor(() => expect(redirectTo).toHaveBeenCalledWith(GITHUB_AUTHORIZE_URL))
  })

  it('connected but not installed: offers to install on repositories', async () => {
    setDemoGitHub({ username: 'octo-ada', installed: false })
    const { user } = renderApp('/settings/github')

    expect(await screen.findByRole('link', { name: '@octo-ada' })).toHaveAttribute(
      'href',
      'https://github.com/octo-ada',
    )
    expect(screen.getByText('Not installed on any repository yet')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Install on repositories' }))
    await waitFor(() => expect(redirectTo).toHaveBeenCalledWith(GITHUB_INSTALL_URL))
  })

  it('installed: links to GitHub to manage repositories', async () => {
    setDemoGitHub({ username: 'octo-ada', installed: true })
    renderApp('/settings/github')

    expect(await screen.findByText('App installed')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Manage repositories/ })).toHaveAttribute(
      'href',
      'https://github.com/settings/installations/555',
    )
  })

  it('disconnect asks first, then shows Connect GitHub again', async () => {
    setDemoGitHub({ username: 'octo-ada', installed: true })
    const { user } = renderApp('/settings/github')

    await user.click(await screen.findByRole('button', { name: 'Disconnect' }))
    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('Disconnect GitHub?')
    await user.click(within(dialog).getByRole('button', { name: 'Disconnect' }))

    expect(await screen.findByText('GitHub disconnected')).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Connect GitHub' })).toBeInTheDocument()
  })

  it('a GitHub-only account must set a password before it can disconnect', async () => {
    resetAuthMock({ githubOnly: true })
    renderApp('/settings/github')

    expect(
      await screen.findByText('To disconnect, first set a password in the Security tab.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Disconnect' })).not.toBeInTheDocument()
  })
})

describe('GitHub-only account (signed up with GitHub)', () => {
  beforeEach(() => resetAuthMock({ githubOnly: true }))

  it('can set its first password without a current one', async () => {
    const { user } = renderApp('/settings/security')

    expect(await screen.findByRole('heading', { name: 'Set a password' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Current password')).not.toBeInTheDocument()

    await user.type(screen.getByLabelText('New password'), 'First-Passw0rd!')
    await user.type(screen.getByLabelText('Confirm new password'), 'First-Passw0rd!')
    await user.click(screen.getByRole('button', { name: 'Set password' }))

    expect(
      await screen.findByText(
        'Password set. You can now also sign in with your email and password.',
      ),
    ).toBeInTheDocument()
    // Now it has a password: the form becomes the normal "Change password".
    expect(await screen.findByRole('heading', { name: 'Change password' })).toBeInTheDocument()
    expect(screen.getByLabelText('Current password')).toBeInTheDocument()
  })

  it('must set a password before changing its email', async () => {
    const { user } = renderApp('/settings/profile')

    const email = await screen.findByLabelText('Email')
    await user.clear(email)
    await user.type(email, 'new@example.com')
    expect(screen.queryByLabelText('Current password')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Save changes' }))

    expect(
      await screen.findByText('Set a password in the Security tab before changing your email'),
    ).toBeInTheDocument()
  })
})
