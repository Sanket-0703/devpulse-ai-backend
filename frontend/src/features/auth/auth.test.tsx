import { act, screen, within } from '@testing-library/react'

import { api } from '@/lib/api/client'
import { DEMO_PASSWORD, DEMO_USER, endServerSession, resetAuthMock } from '@/test/authMock'
import { renderApp } from '@/test/render'

describe('Sign in', () => {
  beforeEach(() => resetAuthMock({ signedIn: false }))

  it('sends signed-out users to the sign-in page, then back to where they were going', async () => {
    const { user, router } = renderApp('/repositories')

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')

    await user.type(screen.getByLabelText('Email'), DEMO_USER.email)
    await user.type(screen.getByLabelText('Password'), DEMO_PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('heading', { name: 'Repositories' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/repositories')
  })

  it('shows the server error for a wrong password', async () => {
    const { user } = renderApp('/login')

    await user.type(await screen.findByLabelText('Email'), DEMO_USER.email)
    await user.type(screen.getByLabelText('Password'), 'wrong-password')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password')
  })

  it('validates the form before sending anything', async () => {
    const { user } = renderApp('/login')

    await user.click(await screen.findByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('Email is required')).toBeInTheDocument()
    expect(screen.getByText('Password is required')).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toHaveAttribute('aria-invalid', 'true')
  })

  it('can show the password while typing', async () => {
    const { user } = renderApp('/login')

    const password = await screen.findByLabelText('Password')
    expect(password).toHaveAttribute('type', 'password')

    await user.click(screen.getByRole('button', { name: 'Show password' }))

    expect(password).toHaveAttribute('type', 'text')
  })
})

describe('Create account', () => {
  beforeEach(() => resetAuthMock({ signedIn: false }))

  it('creates the account and signs straight in', async () => {
    const { user, router } = renderApp('/register')

    await user.type(await screen.findByLabelText('Name'), 'Katherine Johnson')
    await user.type(screen.getByLabelText('Email'), 'katherine@example.com')
    await user.type(screen.getByLabelText('Password'), 'Passw0rd!23')
    await user.type(screen.getByLabelText('Confirm password'), 'Passw0rd!23')
    await user.click(screen.getByRole('button', { name: 'Create account' }))

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/dashboard')
  })

  it('checks the password rules and that both passwords match', async () => {
    const { user } = renderApp('/register')

    await user.type(await screen.findByLabelText('Name'), 'Grace')
    await user.type(screen.getByLabelText('Email'), 'katherine@example.com')
    await user.type(screen.getByLabelText('Password'), 'short')
    await user.type(screen.getByLabelText('Confirm password'), 'different')
    await user.click(screen.getByRole('button', { name: 'Create account' }))

    expect(await screen.findByText('Password must be at least 8 characters')).toBeInTheDocument()
    expect(screen.getByText("Passwords don't match")).toBeInTheDocument()
  })

  it('shows "email already exists" next to the email field', async () => {
    const { user } = renderApp('/register')

    await user.type(await screen.findByLabelText('Name'), 'Ada again')
    await user.type(screen.getByLabelText('Email'), DEMO_USER.email.toUpperCase())
    await user.type(screen.getByLabelText('Password'), 'Passw0rd!23')
    await user.type(screen.getByLabelText('Confirm password'), 'Passw0rd!23')
    await user.click(screen.getByRole('button', { name: 'Create account' }))

    expect(await screen.findByText('An account with this email already exists')).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toHaveAttribute('aria-invalid', 'true')
  })
})

describe('Signed-in session', () => {
  it('survives a page reload by refreshing from the cookie', async () => {
    // Default mock state: the browser holds a valid refresh cookie.
    renderApp('/dashboard')

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument()
  })

  it('sends signed-in users away from the sign-in page', async () => {
    const { router } = renderApp('/login')

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/dashboard')
  })

  it('shows the user in the account menu and signs out', async () => {
    const { user, router } = renderApp('/dashboard')

    await user.click(await screen.findByRole('button', { name: 'Account menu' }))
    const menu = await screen.findByRole('menu')
    expect(within(menu).getByText(DEMO_USER.name)).toBeInTheDocument()
    expect(within(menu).getByText(DEMO_USER.email)).toBeInTheDocument()

    await user.click(within(menu).getByRole('menuitem', { name: 'Sign out' }))

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
  })

  it('goes back to sign-in with a message when the session ends on the server', async () => {
    const { router } = renderApp('/dashboard')
    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument()

    endServerSession()
    await act(() => api.GET('/api/auth/me'))

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByText('Your session has ended. Please sign in again.')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
  })
})
