import { screen, waitFor, within } from '@testing-library/react'

import { setDemoGitHub } from '@/test/authMock'
import {
  changedFile,
  pullRequest,
  setPullRequestDetails,
  setSyncedPullRequests,
} from '@/test/pullRequestsMock'
import { renderApp } from '@/test/render'
import { repository, setSyncedRepositories } from '@/test/repositoriesMock'
import {
  addFinishedReview,
  failNextPublish,
  finding,
  setReviewSettings,
  setWebhookDeliveries,
  storedReviewSettings,
} from '@/test/reviewsMock'

describe('Settings → Reviews', () => {
  it('saves each preference as soon as it changes', async () => {
    const { user } = renderApp('/settings/reviews')

    const autoReview = await screen.findByRole('switch', {
      name: 'Review pull requests automatically',
    })
    expect(autoReview).toBeChecked()
    const autoPublish = screen.getByRole('switch', { name: 'Publish automatic reviews to GitHub' })
    expect(autoPublish).not.toBeChecked()

    await user.click(autoPublish)
    await waitFor(() => expect(storedReviewSettings().autoPublish).toBe(true))
    expect(await screen.findByText('Review settings saved')).toBeInTheDocument()

    await user.selectOptions(screen.getByRole('combobox', { name: 'What to publish' }), 'HIGH')
    await waitFor(() => expect(storedReviewSettings().minPublishSeverity).toBe('HIGH'))
    expect(storedReviewSettings().autoPublish).toBe(true) // the earlier change is kept
  })

  it('lists the latest webhook deliveries and what happened to them', async () => {
    setWebhookDeliveries([
      {
        event: 'pull_request',
        action: 'synchronize',
        status: 'PROCESSED',
        detail: '#7 synchronize. Review started.',
        receivedAt: new Date().toISOString(),
      },
      {
        event: 'pull_request',
        action: 'labeled',
        status: 'IGNORED',
        detail: 'Pull request action "labeled" isn\'t used.',
        receivedAt: new Date().toISOString(),
      },
    ])
    renderApp('/settings/reviews')

    const list = await screen.findByRole('list', { name: 'Webhook deliveries' })
    const [first, second] = within(list).getAllByRole('listitem')
    expect(first).toHaveTextContent('pull_request.synchronize')
    expect(within(first).getByText('processed')).toBeInTheDocument()
    expect(first).toHaveTextContent('#7 synchronize. Review started.')
    expect(within(second).getByText('ignored')).toBeInTheDocument()
  })

  it('says when webhooks are not set up on the server, or nothing arrived yet', async () => {
    setReviewSettings({ webhooksConfigured: false })
    renderApp('/settings/reviews')

    expect(await screen.findByText(/Webhooks aren.t set up on this server yet/)).toBeInTheDocument()
    expect(await screen.findByText(/No deliveries yet/)).toBeInTheDocument()
  })

  it('is one of the settings tabs', async () => {
    const { user, router } = renderApp('/settings/profile')

    const tabs = await screen.findByRole('navigation', { name: 'Settings sections' })
    await user.click(within(tabs).getByRole('link', { name: 'Reviews' }))

    expect(router.state.location.pathname).toBe('/settings/reviews')
  })
})

describe('Publishing a review to GitHub', () => {
  function reviewedPullRequest() {
    setDemoGitHub({ username: 'octo-ada', installed: true })
    const repo = repository('ada/backend')
    setSyncedRepositories([repo])
    setSyncedPullRequests(repo.id, [pullRequest(7)])
    setPullRequestDetails(repo.id, 7, {
      files: [changedFile('src/App.java', { patch: '@@ -1 +1,2 @@\n x\n+y' })],
    })
    addFinishedReview(repo.id, 7, [
      finding('SQL built from strings', { severity: 'HIGH', line: 2 }),
      finding('Debug output', { severity: 'LOW', line: 2 }),
    ])
    return repo
  }

  it('asks first, then publishes and links to the review on GitHub', async () => {
    const repo = reviewedPullRequest()
    const { user } = renderApp(`/repositories/${repo.id}/pulls/7`)

    await user.click(await screen.findByRole('button', { name: 'Publish to GitHub' }))
    const dialog = await screen.findByRole('alertdialog', {
      name: 'Publish this review to GitHub?',
    })
    expect(dialog).toHaveTextContent('medium severity or above')

    await user.click(within(dialog).getByRole('button', { name: 'Publish' }))

    expect(await screen.findByText('Published to GitHub with 1 comment')).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: /View on GitHub/ })).toHaveAttribute(
      'href',
      'https://github.com/ada/backend/pull/7#pullrequestreview-1',
    )
    expect(screen.queryByRole('button', { name: 'Publish to GitHub' })).not.toBeInTheDocument()
  })

  it('cancelling publishes nothing, and a GitHub refusal is explained', async () => {
    const repo = reviewedPullRequest()
    failNextPublish()
    const { user } = renderApp(`/repositories/${repo.id}/pulls/7`)

    await user.click(await screen.findByRole('button', { name: 'Publish to GitHub' }))
    await user.click(await screen.findByRole('button', { name: 'Cancel' }))
    expect(screen.queryByText(/Published to GitHub/)).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Publish to GitHub' }))
    await user.click(await screen.findByRole('button', { name: 'Publish' }))

    expect(await screen.findByText('GitHub refused the request.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Publish to GitHub' })).toBeInTheDocument()
  })

  it('marks reviews started by a push as automatic', async () => {
    setDemoGitHub({ username: 'octo-ada', installed: true })
    const repo = repository('ada/backend')
    setSyncedRepositories([repo])
    setSyncedPullRequests(repo.id, [pullRequest(7)])
    setPullRequestDetails(repo.id, 7, { files: [] })
    addFinishedReview(repo.id, 7, [], { triggeredBy: 'WEBHOOK' })
    renderApp(`/repositories/${repo.id}/pulls/7`)

    expect(await screen.findByText('Automatic')).toBeInTheDocument()
  })
})
