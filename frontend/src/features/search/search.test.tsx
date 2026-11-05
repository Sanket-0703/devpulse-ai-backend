import { screen, waitFor, within } from '@testing-library/react'

import { setDemoGitHub } from '@/test/authMock'
import { renderApp } from '@/test/render'
import { repository, setSyncedRepositories } from '@/test/repositoriesMock'
import { addFinishedReview } from '@/test/reviewsMock'
import { pullRequest, setPullRequestDetails, setSyncedPullRequests } from '@/test/pullRequestsMock'
import { lastSearch, searchResult, setSearchResults } from '@/test/searchMock'

function indexedRepository() {
  setDemoGitHub({ username: 'octo-ada', installed: true })
  const repo = repository('ada/backend', {
    indexStatus: 'SUCCEEDED',
    indexedAt: new Date().toISOString(),
    indexedCommitSha: 'c0ffee1234567890',
    indexedFiles: 203,
    indexedChunks: 657,
  })
  setSyncedRepositories([repo])
  return repo
}

describe('Code index on the repository page', () => {
  it('indexes a repository and shows what was indexed', async () => {
    setDemoGitHub({ username: 'octo-ada', installed: true })
    const repo = repository('ada/backend')
    setSyncedRepositories([repo])
    const { user } = renderApp(`/repositories/${repo.id}`)

    expect(await screen.findByText('Not indexed yet.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Index code' }))

    // The mock finishes on the next read; the page polls while RUNNING.
    expect(
      await screen.findByText(
        /42 files · 130 chunks · indexed .* · commit c0ffee1/,
        {},
        { timeout: 4000 },
      ),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Re-index' })).toBeEnabled()
    expect(screen.getByRole('link', { name: 'Search this repository' })).toHaveAttribute(
      'href',
      `/search?repo=${repo.id}`,
    )
  })

  it('explains a failed index', async () => {
    setDemoGitHub({ username: 'octo-ada', installed: true })
    const repo = repository('ada/backend', {
      indexStatus: 'FAILED',
      indexError: "Couldn't download the repository from GitHub. Please try again.",
    })
    setSyncedRepositories([repo])
    renderApp(`/repositories/${repo.id}`)

    expect(
      await screen.findByText("Couldn't download the repository from GitHub. Please try again."),
    ).toBeInTheDocument()
  })
})

describe('Code search page', () => {
  it('searches by meaning and shows the code with its line numbers', async () => {
    indexedRepository()
    setSearchResults([
      searchResult('src/main/java/com/devpulse/backend/security/JwtService.java', { score: 0.62 }),
      searchResult('src/main/java/com/devpulse/backend/security/JwtAuthenticationFilter.java', {
        score: 0.58,
        startLine: 40,
        endLine: 42,
      }),
    ])
    const { user, router } = renderApp('/search')

    await user.type(
      await screen.findByRole('searchbox', { name: 'Search code' }),
      'where are JWT tokens validated',
    )
    await user.click(screen.getByRole('button', { name: 'Search' }))

    const results = await screen.findByRole('list', { name: 'Results' })
    const [first, second] = within(results).getAllByRole('listitem')
    expect(first).toHaveTextContent('security/JwtService.java:10–12')
    expect(first).toHaveTextContent('62% match')
    expect(first).toHaveTextContent('10public boolean isValid(String jwt) {')
    expect(within(first).getByRole('link', { name: /GitHub/ })).toHaveAttribute(
      'href',
      expect.stringContaining('JwtService.java#L10-L12'),
    )
    expect(second).toHaveTextContent('40public boolean isValid')

    expect(router.state.location.search).toBe('?q=where+are+JWT+tokens+validated')
    expect(lastSearch()?.get('q')).toBe('where are JWT tokens validated')
  })

  it('can search one repository, and offers examples', async () => {
    const repo = indexedRepository()
    setSearchResults([searchResult('src/App.java')])
    const { user } = renderApp('/search')

    await screen.findByRole('option', { name: 'ada/backend' }) // the indexed repositories have loaded
    await user.selectOptions(screen.getByRole('combobox', { name: 'Repository' }), String(repo.id))
    await user.click(screen.getByRole('button', { name: 'how are passwords hashed' }))

    await screen.findByRole('list', { name: 'Results' })
    expect(lastSearch()?.get('repositoryId')).toBe(String(repo.id))
    expect(lastSearch()?.get('q')).toBe('how are passwords hashed')
  })

  it('says when nothing matches', async () => {
    indexedRepository()
    renderApp('/search?q=quantum%20teleportation')

    expect(await screen.findByRole('heading', { name: 'No matches' })).toBeInTheDocument()
  })

  it('says when nothing is indexed yet', async () => {
    setDemoGitHub({ username: 'octo-ada', installed: true })
    setSyncedRepositories([repository('ada/frontend')]) // not indexed
    renderApp('/search')

    expect(await screen.findByRole('heading', { name: 'Nothing indexed yet' })).toBeInTheDocument()
  })
})

describe('Review context', () => {
  it('shows how much related code a review used', async () => {
    const repo = indexedRepository()
    setSyncedPullRequests(repo.id, [pullRequest(7)])
    setPullRequestDetails(repo.id, 7, { files: [] })
    addFinishedReview(repo.id, 7, [], { contextChunks: 3 })
    renderApp(`/repositories/${repo.id}/pulls/7`)

    await waitFor(() =>
      expect(screen.getByText(/3 related code snippets as context/)).toBeInTheDocument(),
    )
  })
})
