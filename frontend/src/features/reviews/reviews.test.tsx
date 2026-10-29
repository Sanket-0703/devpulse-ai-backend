import { screen, waitFor, within } from '@testing-library/react'

import { enableDemoMode, resetAuthMock, setDemoGitHub } from '@/test/authMock'
import {
  changedFile,
  pullRequest,
  setPullRequestDetails,
  setSyncedPullRequests,
} from '@/test/pullRequestsMock'
import { renderApp } from '@/test/render'
import { repository, setSyncedRepositories } from '@/test/repositoriesMock'
import { addFinishedReview, finding, setNextReviewResult } from '@/test/reviewsMock'

const PATCH = '@@ -1,2 +1,3 @@\n line one\n+String sql = "SELECT " + id;\n line three'

/** Ada's repository with pull request #7 changing src/App.java. */
function pullRequestSeven() {
  setDemoGitHub({ username: 'octo-ada', installed: true })
  const repo = repository('ada/backend')
  setSyncedRepositories([repo])
  setSyncedPullRequests(repo.id, [pullRequest(7, { title: 'Add order lookup' })])
  setPullRequestDetails(repo.id, 7, {
    files: [changedFile('src/App.java', { additions: 1, deletions: 0, patch: PATCH })],
  })
  return repo
}

describe('AI review on the pull request page', () => {
  it('runs a review and shows its findings next to the changed line', async () => {
    const repo = pullRequestSeven()
    setNextReviewResult({
      findings: [
        finding('SQL built from strings', { source: 'RULE', severity: 'HIGH', line: 2 }),
        finding('No tests for the new query', { severity: 'LOW', line: undefined }),
      ],
    })
    const { user } = renderApp(`/repositories/${repo.id}/pulls/7`)

    expect(await screen.findByText('No review yet.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Run AI review' }))

    // Polls (every 1.5 s) until done, then shows the counts and the findings.
    expect(await screen.findByText('1 high', {}, { timeout: 4000 })).toBeInTheDocument()
    expect(screen.getByText('1 low')).toBeInTheDocument()
    expect(screen.getByText(/free \(mock AI\)/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Review again' })).toBeEnabled()

    // The line finding sits in the diff, right under line 2.
    const diff = screen.getByRole('table', { name: 'Changes in src/App.java' })
    const rows = within(diff).getAllByRole('row')
    const line2 = rows.findIndex((row) => row.textContent?.startsWith('2+'))
    expect(
      within(rows[line2 + 1]).getByRole('article', { name: 'High: SQL built from strings' }),
    ).toBeInTheDocument()
    expect(within(rows[line2 + 1]).getByText('Security rule')).toBeInTheDocument()

    // The whole-file finding sits above the diff; the panel index links to both.
    expect(
      screen.getByRole('article', { name: 'Low: No tests for the new query' }),
    ).toBeInTheDocument()
    const index = screen.getByRole('list', { name: 'Findings' })
    expect(within(index).getByRole('link', { name: /SQL built from strings/ })).toHaveAttribute(
      'href',
      expect.stringMatching(/^#finding-\d+$/),
    )
    expect(screen.getByText('2 findings')).toBeInTheDocument() // the file header
  })

  it('dismisses a finding, filters by severity and can show dismissed ones again', async () => {
    const repo = pullRequestSeven()
    addFinishedReview(repo.id, 7, [
      finding('SQL built from strings', { severity: 'HIGH', line: 2 }),
      finding('Long method', { severity: 'LOW', line: undefined }),
    ])
    const { user } = renderApp(`/repositories/${repo.id}/pulls/7`)

    const sql = await screen.findByRole('article', { name: 'High: SQL built from strings' })
    await user.click(within(sql).getByRole('button', { name: 'Dismiss' }))

    await waitFor(() =>
      expect(
        screen.queryByRole('article', { name: 'High: SQL built from strings' }),
      ).not.toBeInTheDocument(),
    )
    expect(screen.queryByText('1 high')).not.toBeInTheDocument()

    await user.click(screen.getByRole('checkbox', { name: 'Show dismissed (1)' }))
    const restored = await screen.findByRole('article', { name: 'High: SQL built from strings' })
    expect(within(restored).getByRole('button', { name: 'Restore' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Low' }))
    expect(
      screen.queryByRole('article', { name: 'High: SQL built from strings' }),
    ).not.toBeInTheDocument()
    expect(screen.getByRole('article', { name: 'Low: Long method' })).toBeInTheDocument()
  })

  it('explains a failed review', async () => {
    const repo = pullRequestSeven()
    setNextReviewResult({ error: "Couldn't load the pull request's changes from GitHub." })
    const { user } = renderApp(`/repositories/${repo.id}/pulls/7`)

    await user.click(await screen.findByRole('button', { name: 'Run AI review' }))

    expect(
      await screen.findByText(
        "Couldn't load the pull request's changes from GitHub.",
        {},
        { timeout: 4000 },
      ),
    ).toBeInTheDocument()
    expect(screen.getByText('Failed')).toBeInTheDocument()
  })

  it('switches between earlier reviews', async () => {
    const repo = pullRequestSeven()
    const older = addFinishedReview(repo.id, 7, [finding('Old finding', { line: 2 })])
    addFinishedReview(repo.id, 7, [finding('New finding', { line: 2 })])
    const { user } = renderApp(`/repositories/${repo.id}/pulls/7`)

    expect(await screen.findByRole('article', { name: 'Medium: New finding' })).toBeInTheDocument()

    await user.selectOptions(
      screen.getByRole('combobox', { name: 'Review' }),
      String(older.review.id),
    )

    expect(await screen.findByRole('article', { name: 'Medium: Old finding' })).toBeInTheDocument()
    expect(screen.queryByRole('article', { name: 'Medium: New finding' })).not.toBeInTheDocument()
  })
})

describe('Reviews page', () => {
  it('lists every review with its findings and today’s usage', async () => {
    const repo = pullRequestSeven()
    addFinishedReview(repo.id, 7, [finding('A', { severity: 'CRITICAL' })], {
      pullRequestTitle: 'Add order lookup',
    })
    const { user, router } = renderApp('/reviews')

    expect(await screen.findByText('1 of 20')).toBeInTheDocument() // reviews today
    expect(screen.getByText('Mock AI (free)')).toBeInTheDocument()
    expect(screen.getByText('1 critical')).toBeInTheDocument()

    await user.click(screen.getByRole('link', { name: 'Add order lookup' }))
    expect(router.state.location.pathname).toBe(`/repositories/${repo.id}/pulls/7`)
  })

  it('says when there are no reviews yet', async () => {
    renderApp('/reviews')
    expect(await screen.findByRole('heading', { name: 'No reviews yet' })).toBeInTheDocument()
  })
})

describe('Try the demo', () => {
  it('signs in to the demo account when the backend offers it', async () => {
    resetAuthMock({ signedIn: false })
    enableDemoMode()
    const { user, router } = renderApp('/login')

    await user.click(await screen.findByRole('button', { name: 'Try the demo (no GitHub needed)' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/dashboard'))
  })

  it('is hidden when demo mode is off', async () => {
    resetAuthMock({ signedIn: false })
    renderApp('/login')

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /Try the demo/ })).not.toBeInTheDocument(),
    )
  })
})
