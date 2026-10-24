import { screen, waitFor, within } from '@testing-library/react'

import { getAccessToken } from '@/features/auth/session'
import { DEMO_PASSWORD, DEMO_USER, OTHER_USER, demoAccountPassword } from '@/test/authMock'
import { renderApp } from '@/test/render'

describe('Settings', () => {
  it('opens on the profile section with the current details', async () => {
    const { router } = renderApp('/settings')

    expect(await screen.findByRole('heading', { name: 'Profile' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/settings/profile')
    expect(screen.getByLabelText('Name')).toHaveValue(DEMO_USER.name)
    expect(screen.getByLabelText('Email')).toHaveValue(DEMO_USER.email)
    expect(screen.getByText(/Member since/)).toHaveTextContent('2026')
  })

  it('switches between sections with the tabs', async () => {
    const { user, router } = renderApp('/settings/profile')
    const tabs = await screen.findByRole('navigation', { name: 'Settings sections' })

    await user.click(within(tabs).getByRole('link', { name: 'Security' }))

    expect(await screen.findByRole('heading', { name: 'Change password' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/settings/security')
  })
})

describe('Profile settings', () => {
  it('only enables saving after something changed', async () => {
    const { user } = renderApp('/settings/profile')
    const save = await screen.findByRole('button', { name: 'Save changes' })
    expect(save).toBeDisabled()

    await user.type(screen.getByLabelText('Name'), ' Byron')

    expect(save).toBeEnabled()
  })

  it('saves a new name without asking for the password', async () => {
    const { user } = renderApp('/settings/profile')

    const name = await screen.findByLabelText('Name')
    await user.clear(name)
    await user.type(name, 'Ada King')
    expect(screen.queryByLabelText('Current password')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Save changes' }))

    expect(await screen.findByText('Profile updated')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled()

    // The account menu shows the new name straight away.
    await user.click(screen.getByRole('button', { name: 'Account menu' }))
    expect(await screen.findByRole('menu')).toHaveTextContent('Ada King')
  })

  it('asks for the current password when the email changes', async () => {
    const { user } = renderApp('/settings/profile')

    const email = await screen.findByLabelText('Email')
    await user.clear(email)
    await user.type(email, 'ada.king@example.com')
    expect(screen.getByLabelText('Current password')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(
      await screen.findByText('Enter your current password to change your email'),
    ).toBeInTheDocument()

    await user.type(screen.getByLabelText('Current password'), 'wrong-password')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(await screen.findByText('Current password is incorrect')).toBeInTheDocument()

    await user.clear(screen.getByLabelText('Current password'))
    await user.type(screen.getByLabelText('Current password'), DEMO_PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Save changes' }))

    expect(await screen.findByText('Profile updated')).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toHaveValue('ada.king@example.com')
    // Saved: the email matches again, so the password field goes away.
    expect(screen.queryByLabelText('Current password')).not.toBeInTheDocument()
  })

  it('shows "email already exists" next to the email field', async () => {
    const { user } = renderApp('/settings/profile')

    const email = await screen.findByLabelText('Email')
    await user.clear(email)
    await user.type(email, OTHER_USER.email.toUpperCase())
    await user.type(screen.getByLabelText('Current password'), DEMO_PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Save changes' }))

    expect(await screen.findByText('An account with this email already exists')).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toHaveAttribute('aria-invalid', 'true')
  })

  it('cancel puts the saved values back', async () => {
    const { user } = renderApp('/settings/profile')

    const name = await screen.findByLabelText('Name')
    await user.clear(name)
    await user.type(name, 'Someone else')
    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(name).toHaveValue(DEMO_USER.name)
  })
})

describe('Security settings', () => {
  const NEW_PASSWORD = 'Brand-New-Passw0rd'

  async function fillPasswordForm(
    user: ReturnType<typeof renderApp>['user'],
    current: string,
    next: string,
    confirm = next,
  ) {
    await user.type(await screen.findByLabelText('Current password'), current)
    await user.type(screen.getByLabelText('New password'), next)
    await user.type(screen.getByLabelText('Confirm new password'), confirm)
    await user.click(screen.getByRole('button', { name: 'Change password' }))
  }

  it('checks the new password before sending', async () => {
    const { user } = renderApp('/settings/security')

    await fillPasswordForm(user, DEMO_PASSWORD, DEMO_PASSWORD)
    expect(
      await screen.findByText('New password must be different from the current one'),
    ).toBeInTheDocument()

    await user.clear(screen.getByLabelText('New password'))
    await user.type(screen.getByLabelText('New password'), 'short')
    await user.click(screen.getByRole('button', { name: 'Change password' }))
    expect(await screen.findByText('Password must be at least 8 characters')).toBeInTheDocument()
    expect(screen.getByText("Passwords don't match")).toBeInTheDocument()
  })

  it('shows a wrong current password next to the field', async () => {
    const { user } = renderApp('/settings/security')

    await fillPasswordForm(user, 'wrong-password', NEW_PASSWORD)

    expect(await screen.findByText('Current password is incorrect')).toBeInTheDocument()
    expect(screen.getByLabelText('Current password')).toHaveAttribute('aria-invalid', 'true')
  })

  it('changes the password and keeps this device signed in with a new session', async () => {
    const { user } = renderApp('/settings/security')
    await screen.findByRole('heading', { name: 'Change password' })
    const tokenBefore = getAccessToken()

    await fillPasswordForm(user, DEMO_PASSWORD, NEW_PASSWORD)

    expect(
      await screen.findByText('Password changed. Your other devices have been signed out.'),
    ).toBeInTheDocument()
    expect(demoAccountPassword()).toBe(NEW_PASSWORD)
    expect(getAccessToken()).not.toBe(tokenBefore)
    expect(screen.getByLabelText('Current password')).toHaveValue('')
    // Still signed in.
    expect(screen.getByRole('heading', { name: 'Settings' })).toBeInTheDocument()
  })

  it('signing out everywhere asks first, then ends the session', async () => {
    const { user, router } = renderApp('/settings/security')

    await user.click(await screen.findByRole('button', { name: 'Sign out everywhere' }))
    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('Sign out on every device?')

    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument())
    expect(router.state.location.pathname).toBe('/settings/security')

    await user.click(screen.getByRole('button', { name: 'Sign out everywhere' }))
    await user.click(
      within(await screen.findByRole('alertdialog')).getByRole('button', {
        name: 'Sign out everywhere',
      }),
    )

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByText('Signed out on every device.')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
  })
})
