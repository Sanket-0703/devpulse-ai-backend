import { http, HttpResponse } from 'msw'

import { API_BASE_URL } from '@/lib/api/config'
import type { components } from '@/lib/api/schema'

import { authenticated, error } from './authMock'

type Repository = components['schemas']['RepositoryResponse']
type SyncStatus = components['schemas']['SyncStatusResponse']

/**
 * In-memory imitation of /api/github/repositories* for the signed-in user.
 * Connection and installation come from authMock's account.github.
 * A started sync reports RUNNING once, then finishes on the next status poll.
 */

const state = {
  repositories: [] as Repository[],
  status: 'NEVER' as SyncStatus['status'],
  lastSyncedAt: undefined as string | undefined,
  syncError: undefined as string | undefined,
  /** Repositories a running sync will deliver when it finishes. */
  pendingSync: null as Repository[] | null,
  cooldown: false,
  failNextToggle: false,
}

export function resetRepositoriesMock() {
  state.repositories = []
  state.status = 'NEVER'
  state.lastSyncedAt = undefined
  state.syncError = undefined
  state.pendingSync = null
  state.cooldown = false
  state.failNextToggle = false
}

let nextId = 1

export function repository(fullName: string, overrides: Partial<Repository> = {}): Repository {
  const id = nextId++
  return {
    id,
    githubId: 1000 + id,
    name: fullName.split('/')[1] ?? fullName,
    fullName,
    description: undefined,
    isPrivate: false,
    fork: false,
    archived: false,
    htmlUrl: `https://github.com/${fullName}`,
    defaultBranch: 'main',
    language: 'TypeScript',
    pushedAt: '2026-10-20T10:00:00Z',
    syncedAt: '2026-10-25T09:00:00Z',
    reviewEnabled: false,
    ...overrides,
  }
}

/** Repositories already synced for the demo user. */
export function setSyncedRepositories(repositories: Repository[]) {
  state.repositories = repositories
  state.status = 'SUCCEEDED'
  state.lastSyncedAt = new Date(Date.now() - 5 * 60_000).toISOString()
}

/** What the next "Sync now" will bring back from GitHub. */
export function setNextSyncResult(repositories: Repository[]) {
  state.pendingSync = repositories
}

export function failNextReviewToggle() {
  state.failNextToggle = true
}

export function storedRepository(id: number) {
  return state.repositories.find((r) => r.id === id)
}

const notConnected = (path: string) => error(401, 'Access token has expired', path)

function status(installed: boolean, connected = true): SyncStatus {
  return {
    connected,
    installed,
    status: state.status,
    lastSyncedAt: state.lastSyncedAt,
    error: state.syncError,
    repositoryCount: state.repositories.length,
  }
}

export const repositoriesHandlers = [
  http.get(`${API_BASE_URL}/api/github/repositories/sync`, ({ request }) => {
    const account = authenticated(request)
    if (!account) return notConnected('/api/github/repositories/sync')
    if (!account.github) return HttpResponse.json(status(false, false))

    const response = status(account.github.installed)
    if (state.status === 'RUNNING') {
      // Finish the sync: the next poll sees the result.
      state.repositories = state.pendingSync ?? state.repositories
      state.pendingSync = null
      state.status = 'SUCCEEDED'
      state.lastSyncedAt = new Date().toISOString()
    }
    return HttpResponse.json(response)
  }),

  http.post(`${API_BASE_URL}/api/github/repositories/sync`, ({ request }) => {
    const account = authenticated(request)
    if (!account) return notConnected('/api/github/repositories/sync')
    if (!account.github?.installed) {
      return error(
        409,
        'Install the DevPulse app on at least one repository first.',
        '/api/github/repositories/sync',
      )
    }
    if (state.cooldown) {
      return error(
        429,
        'You can sync once a minute. Try again in 42s.',
        '/api/github/repositories/sync',
      )
    }
    state.cooldown = true
    state.status = 'RUNNING'
    return HttpResponse.json(status(true), { status: 202 })
  }),

  http.get(`${API_BASE_URL}/api/github/repositories`, ({ request }) => {
    if (!authenticated(request)) return notConnected('/api/github/repositories')
    const url = new URL(request.url)
    const q = url.searchParams.get('q')?.toLowerCase() ?? ''
    const reviewEnabled = url.searchParams.get('reviewEnabled')
    const sort = url.searchParams.get('sort') ?? 'NAME'
    const page = Number(url.searchParams.get('page') ?? 0)
    const size = Number(url.searchParams.get('size') ?? 20)

    let items = state.repositories.filter(
      (r) =>
        (!q || r.fullName.toLowerCase().includes(q) || r.description?.toLowerCase().includes(q)) &&
        (reviewEnabled === null || String(r.reviewEnabled) === reviewEnabled),
    )
    items = [...items].sort((a, b) =>
      sort === 'PUSHED'
        ? (b.pushedAt ?? '').localeCompare(a.pushedAt ?? '')
        : a.fullName.localeCompare(b.fullName),
    )

    return HttpResponse.json({
      content: items.slice(page * size, page * size + size),
      page,
      size,
      totalElements: items.length,
      totalPages: Math.ceil(items.length / size),
    })
  }),

  http.get(`${API_BASE_URL}/api/github/repositories/:id`, ({ request, params }) => {
    if (!authenticated(request)) return notConnected('/api/github/repositories')
    const repo = storedRepository(Number(params.id))
    return repo
      ? HttpResponse.json(repo)
      : error(404, 'Repository not found', `/api/github/repositories/${params.id}`)
  }),

  http.patch(`${API_BASE_URL}/api/github/repositories/:id`, async ({ request, params }) => {
    if (!authenticated(request)) return notConnected('/api/github/repositories')
    const repo = storedRepository(Number(params.id))
    if (!repo) return error(404, 'Repository not found', `/api/github/repositories/${params.id}`)
    if (state.failNextToggle) {
      state.failNextToggle = false
      return error(500, 'Something went wrong', `/api/github/repositories/${params.id}`)
    }
    const body = (await request.json()) as { reviewEnabled: boolean }
    repo.reviewEnabled = body.reviewEnabled
    return HttpResponse.json(repo)
  }),
]
