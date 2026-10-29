import { screen, within } from '@testing-library/react'

import { setDashboard } from '@/test/dashboardMock'
import { limitPlayground } from '@/test/playgroundMock'
import { renderApp } from '@/test/render'

describe('Dashboard', () => {
  it('guides a new account through the first steps', async () => {
    renderApp('/dashboard')

    expect(await screen.findByRole('heading', { name: 'Get started' })).toBeInTheDocument()
    expect(screen.getByText('0 of 5 steps done')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Connect your GitHub account' })).toHaveAttribute(
      'href',
      '/settings/github',
    )
    expect(screen.getByText('No open findings.')).toBeInTheDocument()
    expect(screen.getByText(/No reviews yet/)).toBeInTheDocument()
  })

  it('shows totals, the severity chart, recent reviews and usage', async () => {
    setDashboard({
      onboarding: {
        githubConnected: true,
        appInstalled: true,
        repositoriesSynced: true,
        reviewEnabled: true,
        firstReviewDone: true,
      },
      stats: {
        repositories: 4,
        reviewEnabledRepositories: 2,
        openPullRequests: 7,
        reviewsLast30Days: 12,
        reviewsTotal: 15,
      },
      openFindings: { critical: 1, high: 3, medium: 0, low: 6 },
      recentReviews: [
        {
          id: 9,
          repositoryId: 3,
          repositoryFullName: 'ada/backend',
          pullRequestNumber: 42,
          pullRequestTitle: 'Add checkout',
          headSha: 'abc1234',
          status: 'SUCCEEDED',
          provider: 'mock',
          model: 'mock-reviewer',
          filesReviewed: 3,
          filesSkipped: 0,
          chunks: 1,
          truncated: false,
          promptTokens: 900,
          completionTokens: 100,
          estimatedCostUsd: 0,
          openFindings: { critical: 0, high: 2, medium: 0, low: 1 },
          createdAt: new Date().toISOString(),
        },
      ],
    })
    renderApp('/dashboard')

    // Everything done: no checklist.
    expect(await screen.findByRole('link', { name: 'Add checkout' })).toHaveAttribute(
      'href',
      '/repositories/3/pulls/42',
    )
    expect(screen.queryByRole('heading', { name: 'Get started' })).not.toBeInTheDocument()

    const totals = screen.getByRole('region', { name: 'Totals' })
    expect(within(totals).getByText('Open pull requests').nextSibling).toHaveTextContent('7')

    const chart = screen.getByLabelText('Open findings by severity')
    expect(within(chart).getByText('High').nextSibling?.nextSibling).toHaveTextContent('3')
    expect(within(chart).getByText('Low').nextSibling?.nextSibling).toHaveTextContent('6')

    expect(screen.getByText('0 of 30 runs')).toBeInTheDocument() // Playground usage
  })
})

describe('Playground', () => {
  it('reviews a snippet and shows each finding under its line', async () => {
    const { user } = renderApp('/playground')

    await user.click(await screen.findByRole('button', { name: 'Load an example' }))
    expect((screen.getByRole('textbox', { name: 'Code' }) as HTMLTextAreaElement).value).toContain(
      'SELECT * FROM users',
    )
    await user.click(screen.getByRole('button', { name: 'Review' }))

    const diff = await screen.findByRole('table', { name: 'Changes in your snippet' })
    const rows = within(diff).getAllByRole('row')
    const line3 = rows.findIndex((row) => row.textContent?.startsWith('3+'))
    expect(
      within(rows[line3 + 1]).getByRole('article', { name: 'High: SQL built from strings' }),
    ).toBeInTheDocument()
    const line2 = rows.findIndex((row) => row.textContent?.startsWith('2+'))
    expect(
      within(rows[line2 + 1]).getByRole('article', { name: 'Low: Debug output left in' }),
    ).toBeInTheDocument()
    expect(screen.getByText(/840 tokens · free \(mock AI\) · Java/)).toBeInTheDocument()
    // No dismiss buttons: Playground findings aren't stored.
    expect(screen.queryByRole('button', { name: 'Dismiss' })).not.toBeInTheDocument()
  })

  it('needs some code, counts characters, and explains rate limits', async () => {
    limitPlayground()
    const { user } = renderApp('/playground')

    const review = await screen.findByRole('button', { name: 'Review' })
    expect(review).toBeDisabled()

    await user.type(screen.getByRole('textbox', { name: 'Code' }), 'print(1)')
    expect(screen.getByText('8 / 20,000 characters')).toBeInTheDocument()
    await user.click(review)

    expect(
      await screen.findByText('Slow down: at most 5 Playground runs a minute.'),
    ).toBeInTheDocument()
  })
})
