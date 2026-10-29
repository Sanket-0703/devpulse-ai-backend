import { api } from '@/lib/api/client'
import { unwrap } from '@/lib/api/errors'
import type { components } from '@/lib/api/schema'

export type Repository = components['schemas']['RepositoryResponse']
export type RepositoryPage = components['schemas']['PageResponseRepositoryResponse']
export type SyncStatus = components['schemas']['SyncStatusResponse']

export type ReviewFilter = 'all' | 'on' | 'off'
export type RepositorySort = 'NAME' | 'PUSHED'

export type RepositoryListParams = {
  q: string
  filter: ReviewFilter
  sort: RepositorySort
  /** Zero-based. */
  page: number
}

export const PAGE_SIZE = 20

// Query keys: everything repository-related starts with 'repositories', so
// one invalidateQueries({ queryKey: ['repositories'] }) refreshes it all.
export const repositoryKeys = {
  all: ['repositories'] as const,
  list: (params: RepositoryListParams) => ['repositories', 'list', params] as const,
  detail: (id: number) => ['repositories', 'detail', id] as const,
  sync: ['repositories', 'sync'] as const,
}

export async function listRepositories(params: RepositoryListParams): Promise<RepositoryPage> {
  return unwrap(
    await api.GET('/api/github/repositories', {
      params: {
        query: {
          q: params.q || undefined,
          reviewEnabled: params.filter === 'all' ? undefined : params.filter === 'on',
          sort: params.sort,
          page: params.page,
          size: PAGE_SIZE,
        },
      },
    }),
  )
}

export async function getRepository(id: number): Promise<Repository> {
  return unwrap(await api.GET('/api/github/repositories/{id}', { params: { path: { id } } }))
}

export async function setReviewEnabled(id: number, reviewEnabled: boolean): Promise<Repository> {
  return unwrap(
    await api.PATCH('/api/github/repositories/{id}', {
      params: { path: { id } },
      body: { reviewEnabled },
    }),
  )
}

export async function getSyncStatus(): Promise<SyncStatus> {
  return unwrap(await api.GET('/api/github/repositories/sync'))
}

export async function startSync(): Promise<SyncStatus> {
  return unwrap(await api.POST('/api/github/repositories/sync'))
}
