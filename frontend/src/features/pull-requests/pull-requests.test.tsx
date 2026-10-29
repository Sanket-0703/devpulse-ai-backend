import { screen, waitFor, within } from '@testing-library/react'

import { setDemoGitHub } from '@/test/authMock'
import {
  changedFile,
  failPullRequestFiles,
  pullRequest,
  setNextPullRequestSync,
  setPullRequestDetails,
  setSyncedPullRequests,
} from '@/test/pullRequestsMock'
import { renderApp } from '@/test/render'
import { repository, setSyncedRepositories } from '@/test/repositoriesMock'

function oneRepository() {
  setDemoGitHub({ username: 'octo-ada', installed: true })
  const repo = repository('ada/backend')
  setSyncedRepositories([repo])
  return repo
}

describe('Pull requests on the repository page', () => {
  it('offers a first sync, then lists what it brought', async () => {
    const repo = oneRepository()
    setNextPullRequestSync(repo.id, [
      pullRequest(2, { title: 'Add login page', draft: true }),
      pullRequest(1, { title: 'Initial commit', state: 'MERGED' }),
    ])
    const { user } = renderApp(`/repositories/${repo.id}`)

    expect(
      await screen.findByRole('heading', { name: "Pull requests aren't synced yet" }),
    ).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Sync pull requests' }))

    const login = (await screen.findByRole('link', { name: 'Add login page' })).closest('li')!
    expect(within(login).getByText('Draft')).toBeInTheDocument()
    expect(within(login).getByText('#2')).toBeInTheDocument()
    expect(within(login).getByText('feature-2 → main')).toBeInTheDocument()
    const initial = screen.getByRole('link', { name: 'Initial commit' }).closest('li')!
    expect(within(initial).getByText('Merged')).toBeInTheDocument()

    expect(await screen.findByText('Synced 2 pull requests')).toBeInTheDocument()
    expect(screen.getByText('Synced just now')).toBeInTheDocument()
  })

  it('filters by state and searches, keeping both in the URL', async () => {
    const repo = oneRepository()
    setSyncedPullRequests(repo.id, [
      pullRequest(3, { title: 'Fix NPE in sync', state: 'MERGED' }),
      pullRequest(2, { title: 'Add login page' }),
      pullRequest(1, { title: 'Try a parser', state: 'CLOSED' }),
    ])
    const { user, router } = renderApp(`/repositories/${repo.id}`)
    expect(await screen.findByText('Synced 2 minutes ago')).toBeInTheDocument()

    await user.click(await screen.findByRole('button', { name: 'Merged' }))
    await waitFor(() =>
      expect(screen.queryByRole('link', { name: 'Add login page' })).not.toBeInTheDocument(),
    )
    expect(screen.getByRole('link', { name: 'Fix NPE in sync' })).toBeInTheDocument()
    expect(router.state.location.search).toBe('?state=MERGED')

    await user.click(screen.getByRole('button', { name: 'All' }))
    await user.type(screen.getByRole('searchbox', { name: 'Search pull requests' }), '#1')
    await waitFor(() => expect(router.state.location.search).toBe('?q=%231'))
    // The previous results stay on screen until the search answers (keepPreviousData).
    await waitFor(() =>
      expect(screen.queryByRole('link', { name: 'Fix NPE in sync' })).not.toBeInTheDocument(),
    )
    expect(screen.getByRole('link', { name: 'Try a parser' })).toBeInTheDocument()

    await user.clear(screen.getByRole('searchbox', { name: 'Search pull requests' }))
    await user.type(screen.getByRole('searchbox', { name: 'Search pull requests' }), 'nothing')
    expect(
      await screen.findByRole('heading', { name: 'No pull requests match' }),
    ).toBeInTheDocument()
  })

  it('says when a synced repository has no pull requests', async () => {
    const repo = oneRepository()
    setSyncedPullRequests(repo.id, [])
    renderApp(`/repositories/${repo.id}`)

    expect(await screen.findByRole('heading', { name: 'No pull requests yet' })).toBeInTheDocument()
  })
})

describe('Pull request page', () => {
  it('shows the pull request, its description and the diff of each file', async () => {
    const repo = oneRepository()
    setSyncedPullRequests(repo.id, [
      pullRequest(7, {
        title: 'Add login page',
        state: 'MERGED',
        mergedAt: '2026-10-25T10:00:00Z',
      }),
    ])
    setPullRequestDetails(repo.id, 7, {
      body: 'Adds the login form.\n\nCloses #3.',
      files: [
        changedFile('src/Login.tsx', {
          status: 'added',
          additions: 2,
          deletions: 0,
          patch: '@@ -0,0 +1,2 @@\n+export function Login() {\n+}',
        }),
        changedFile('logo.png', { additions: 0, deletions: 0, blobUrl: 'https://github.com/x' }),
        changedFile('src/new.ts', {
          previousFilename: 'src/old.ts',
          status: 'renamed',
          additions: 0,
          deletions: 0,
        }),
      ],
    })
    const { user } = renderApp(`/repositories/${repo.id}`)

    await user.click(await screen.findByRole('link', { name: 'Add login page' }))

    expect(await screen.findByRole('heading', { name: 'Add login page #7' })).toBeInTheDocument()
    expect(screen.getByText('Merged')).toBeInTheDocument()
    expect(screen.getByText(/Adds the login form/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /ada\/backend/ })).toHaveAttribute(
      'href',
      `/repositories/${repo.id}`,
    )

    // 3 files, +2 −0 in total.
    expect(await screen.findByText('3 files ·', { exact: false })).toBeInTheDocument()

    const diff = screen.getByRole('table', { name: 'Changes in src/Login.tsx' })
    const rows = within(diff).getAllByRole('row').slice(1) // skip the screen-reader header
    expect(rows[0]).toHaveTextContent('@@ -0,0 +1,2 @@')
    expect(rows[1]).toHaveTextContent('1+Added: export function Login() {')

    expect(screen.getByText(/No diff to show: a binary file/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View the file on GitHub' })).toHaveAttribute(
      'href',
      'https://github.com/x',
    )
    expect(screen.getByText('File renamed without changes.')).toBeInTheDocument()
    expect(screen.getByText('src/old.ts →')).toBeInTheDocument()
  })

  it('explains a GitHub failure while loading the files, with a retry', async () => {
    const repo = oneRepository()
    setSyncedPullRequests(repo.id, [pullRequest(7)])
    failPullRequestFiles()
    renderApp(`/repositories/${repo.id}/pulls/7`)

    expect(
      await screen.findByText("GitHub didn't respond as expected. Please try again in a moment."),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
    expect(screen.getByText('No description provided.')).toBeInTheDocument()
  })

  it('says when a pull request does not exist', async () => {
    const repo = oneRepository()
    setSyncedPullRequests(repo.id, [])
    renderApp(`/repositories/${repo.id}/pulls/42`)

    expect(
      await screen.findByRole('heading', { name: 'Pull request not found' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to the repository' })).toHaveAttribute(
      'href',
      `/repositories/${repo.id}`,
    )
  })
})
