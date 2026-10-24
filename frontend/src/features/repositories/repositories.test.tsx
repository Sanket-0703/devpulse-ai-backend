import { act, screen, waitFor, within } from '@testing-library/react'

import { setDemoGitHub } from '@/test/authMock'
import { renderApp } from '@/test/render'
import {
  failNextReviewToggle,
  repository,
  setNextSyncResult,
  setSyncedRepositories,
  storedRepository,
} from '@/test/repositoriesMock'

function connectAndInstall() {
  setDemoGitHub({ username: 'octo-ada', installed: true })
}

describe('Repositories page — before repositories exist', () => {
  it('asks to connect GitHub first', async () => {
    renderApp('/repositories')

    expect(await screen.findByRole('heading', { name: 'Connect GitHub first' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to Settings → GitHub' })).toHaveAttribute(
      'href',
      '/settings/github',
    )
  })

  it('asks to install the app when GitHub is connected but the app is not', async () => {
    setDemoGitHub({ username: 'octo-ada', installed: false })
    renderApp('/repositories')

    expect(
      await screen.findByRole('heading', { name: 'Install the DevPulse app' }),
    ).toBeInTheDocument()
  })

  it('offers Sync now when nothing has been synced yet', async () => {
    connectAndInstall()
    renderApp('/repositories')

    expect(await screen.findByRole('heading', { name: 'No repositories yet' })).toBeInTheDocument()
    expect(screen.getByText('Not synced yet')).toBeInTheDocument()
  })
})

describe('Repositories page — list', () => {
  beforeEach(connectAndInstall)

  it('lists repositories with their details and when they were synced', async () => {
    setSyncedRepositories([
      repository('ada/backend', {
        description: 'Spring Boot API',
        isPrivate: true,
        language: 'Java',
      }),
      repository('ada/old-fork', { fork: true, archived: true }),
    ])
    renderApp('/repositories')

    const backend = (await screen.findByRole('link', { name: 'ada/backend' })).closest('li')!
    expect(within(backend).getByText('Private')).toBeInTheDocument()
    expect(within(backend).getByText('Spring Boot API')).toBeInTheDocument()
    expect(within(backend).getByText('Java')).toBeInTheDocument()

    const fork = screen.getByRole('link', { name: 'ada/old-fork' }).closest('li')!
    expect(within(fork).getByText('Fork')).toBeInTheDocument()
    expect(within(fork).getByText('Archived')).toBeInTheDocument()

    expect(screen.getByText('Synced 5 minutes ago')).toBeInTheDocument()
  })

  it('searches as you type and keeps the search in the URL', async () => {
    setSyncedRepositories([repository('ada/backend'), repository('ada/frontend')])
    const { user, router } = renderApp('/repositories')

    await user.type(await screen.findByRole('searchbox', { name: 'Search repositories' }), 'back')

    await waitFor(() =>
      expect(screen.queryByRole('link', { name: 'ada/frontend' })).not.toBeInTheDocument(),
    )
    expect(screen.getByRole('link', { name: 'ada/backend' })).toBeInTheDocument()
    expect(router.state.location.search).toBe('?q=back')
  })

  it('keeps the search box in step with back and forward', async () => {
    setSyncedRepositories([repository('ada/backend'), repository('ada/frontend')])
    const { router } = renderApp('/repositories?q=back')

    const search = await screen.findByRole('searchbox', { name: 'Search repositories' })
    expect(search).toHaveValue('back')

    await act(() => router.navigate('/repositories'))

    expect(search).toHaveValue('')
    expect(await screen.findByRole('link', { name: 'ada/frontend' })).toBeInTheDocument()
  })

  it('says so when nothing matches', async () => {
    setSyncedRepositories([repository('ada/backend')])
    renderApp('/repositories?q=nothing-like-this')

    expect(
      await screen.findByRole('heading', { name: 'No repositories match' }),
    ).toBeInTheDocument()
  })

  it('filters by review setting and sorts by recent pushes', async () => {
    setSyncedRepositories([
      repository('ada/a-old', { pushedAt: '2026-01-01T00:00:00Z', reviewEnabled: true }),
      repository('ada/b-new', { pushedAt: '2026-10-24T00:00:00Z' }),
    ])
    const { user } = renderApp('/repositories')
    await screen.findByRole('link', { name: 'ada/a-old' })

    await user.click(screen.getByRole('button', { name: 'Review on' }))
    await waitFor(() =>
      expect(screen.queryByRole('link', { name: 'ada/b-new' })).not.toBeInTheDocument(),
    )
    expect(screen.getByRole('button', { name: 'Review on' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )

    await user.click(screen.getByRole('button', { name: 'All' }))
    await user.click(screen.getByRole('button', { name: 'Recently pushed' }))
    await waitFor(() => {
      const names = screen.getAllByRole('link', { name: /^ada\// }).map((a) => a.textContent)
      expect(names).toEqual(['ada/b-new', 'ada/a-old'])
    })
  })

  it('pages through long lists', async () => {
    setSyncedRepositories(
      Array.from({ length: 25 }, (_, i) => repository(`ada/repo-${String(i).padStart(2, '0')}`)),
    )
    const { user, router } = renderApp('/repositories')

    expect(await screen.findByText(/Page 1 of 2/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Next' }))

    expect(await screen.findByRole('link', { name: 'ada/repo-24' })).toBeInTheDocument()
    expect(screen.getByText(/Page 2 of 2/)).toBeInTheDocument()
    expect(router.state.location.search).toBe('?page=2')
  })

  it('turns AI review on instantly and saves it', async () => {
    const repo = repository('ada/backend')
    setSyncedRepositories([repo])
    const { user } = renderApp('/repositories')

    const toggle = await screen.findByRole('switch', { name: 'AI review for ada/backend' })
    expect(toggle).toHaveAttribute('aria-checked', 'false')

    await user.click(toggle)

    expect(toggle).toHaveAttribute('aria-checked', 'true')
    await waitFor(() => expect(storedRepository(repo.id)?.reviewEnabled).toBe(true))
  })

  it('puts the switch back and explains when saving fails', async () => {
    setSyncedRepositories([repository('ada/backend')])
    failNextReviewToggle()
    const { user } = renderApp('/repositories')

    const toggle = await screen.findByRole('switch', { name: 'AI review for ada/backend' })
    await user.click(toggle)

    expect(await screen.findByText('Something went wrong')).toBeInTheDocument()
    await waitFor(() => expect(toggle).toHaveAttribute('aria-checked', 'false'))
  })
})

describe('Repositories page — sync', () => {
  beforeEach(connectAndInstall)

  it('Sync now shows progress, then the new repositories', async () => {
    setSyncedRepositories([repository('ada/backend')])
    setNextSyncResult([repository('ada/backend'), repository('ada/just-added')])
    const { user } = renderApp('/repositories')
    await screen.findByRole('link', { name: 'ada/backend' })

    await user.click(screen.getByRole('button', { name: 'Sync now' }))

    expect(await screen.findByText('Syncing with GitHub…')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sync now' })).toBeDisabled()
    expect(
      await screen.findByText('Repositories synced', {}, { timeout: 4000 }),
    ).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'ada/just-added' })).toBeInTheDocument()
  })

  it('explains the once-a-minute limit', async () => {
    setSyncedRepositories([repository('ada/backend')])
    const { user } = renderApp('/repositories')
    await screen.findByRole('link', { name: 'ada/backend' })

    await user.click(screen.getByRole('button', { name: 'Sync now' }))
    await screen.findByText('Repositories synced', {}, { timeout: 4000 })
    await user.click(screen.getByRole('button', { name: 'Sync now' }))

    expect(
      await screen.findByText('You can sync once a minute. Try again in 42s.'),
    ).toBeInTheDocument()
  })
})

describe('Repository detail page', () => {
  beforeEach(connectAndInstall)

  it('shows the repository and its review switch', async () => {
    const repo = repository('ada/backend', {
      description: 'Spring Boot API',
      language: 'Java',
      defaultBranch: 'develop',
    })
    setSyncedRepositories([repo])
    const { user } = renderApp(`/repositories/${repo.id}`)

    expect(await screen.findByRole('heading', { name: 'ada/backend' })).toBeInTheDocument()
    expect(screen.getByText('Spring Boot API')).toBeInTheDocument()
    expect(screen.getByText('develop')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Open on GitHub/ })).toHaveAttribute(
      'href',
      'https://github.com/ada/backend',
    )

    await user.click(screen.getByRole('switch', { name: 'AI review for ada/backend' }))
    expect(await screen.findByText('Review on')).toBeInTheDocument()
  })

  it('is reachable from the list', async () => {
    const repo = repository('ada/backend')
    setSyncedRepositories([repo])
    const { user, router } = renderApp('/repositories')

    await user.click(await screen.findByRole('link', { name: 'ada/backend' }))

    expect(await screen.findByRole('heading', { name: 'ada/backend' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe(`/repositories/${repo.id}`)
  })

  it('says when a repository does not exist (or is not yours)', async () => {
    renderApp('/repositories/999999')

    expect(await screen.findByRole('heading', { name: 'Repository not found' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to repositories' })).toHaveAttribute(
      'href',
      '/repositories',
    )
  })
})
