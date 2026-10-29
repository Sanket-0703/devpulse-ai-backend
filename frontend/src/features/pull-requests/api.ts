import { api } from '@/lib/api/client'
import { unwrap } from '@/lib/api/errors'
import type { components } from '@/lib/api/schema'

export type PullRequest = components['schemas']['PullRequestResponse']
export type PullRequestPage = components['schemas']['PageResponsePullRequestResponse']
export type PullRequestDetail = components['schemas']['PullRequestDetailResponse']
export type PullRequestFiles = components['schemas']['PullRequestFilesResponse']
export type ChangedFile = components['schemas']['ChangedFile']
export type PullRequestSync = components['schemas']['PullRequestSyncResponse']
export type PullRequestState = PullRequest['state']

export type StateFilter = 'all' | PullRequestState

export type PullRequestListParams = {
  q: string
  state: StateFilter
  /** Zero-based. */
  page: number
}

export const PAGE_SIZE = 20

// Everything about one repository's pull requests starts with
// ['pull-requests', repositoryId], so a sync can refresh it all at once.
export const pullRequestKeys = {
  repository: (repositoryId: number) => ['pull-requests', repositoryId] as const,
  list: (repositoryId: number, params: PullRequestListParams) =>
    ['pull-requests', repositoryId, 'list', params] as const,
  detail: (repositoryId: number, number: number) =>
    ['pull-requests', repositoryId, 'detail', number] as const,
  files: (repositoryId: number, number: number) =>
    ['pull-requests', repositoryId, 'files', number] as const,
}

export async function listPullRequests(
  repositoryId: number,
  params: PullRequestListParams,
): Promise<PullRequestPage> {
  return unwrap(
    await api.GET('/api/github/repositories/{repositoryId}/pull-requests', {
      params: {
        path: { repositoryId },
        query: {
          q: params.q || undefined,
          state: params.state === 'all' ? undefined : params.state,
          page: params.page,
          size: PAGE_SIZE,
        },
      },
    }),
  )
}

export async function syncPullRequests(repositoryId: number): Promise<PullRequestSync> {
  return unwrap(
    await api.POST('/api/github/repositories/{repositoryId}/pull-requests/sync', {
      params: { path: { repositoryId } },
    }),
  )
}

export async function getPullRequest(
  repositoryId: number,
  number: number,
): Promise<PullRequestDetail> {
  return unwrap(
    await api.GET('/api/github/repositories/{repositoryId}/pull-requests/{number}', {
      params: { path: { repositoryId, number } },
    }),
  )
}

export async function getPullRequestFiles(
  repositoryId: number,
  number: number,
): Promise<PullRequestFiles> {
  return unwrap(
    await api.GET('/api/github/repositories/{repositoryId}/pull-requests/{number}/files', {
      params: { path: { repositoryId, number } },
    }),
  )
}
