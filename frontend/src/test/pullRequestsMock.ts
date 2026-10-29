import { http, HttpResponse } from 'msw'

import { API_BASE_URL } from '@/lib/api/config'
import type { components } from '@/lib/api/schema'

import { authenticated, error } from './authMock'
import { storedRepository } from './repositoriesMock'

type PullRequest = components['schemas']['PullRequestResponse']
type ChangedFile = components['schemas']['ChangedFile']

/**
 * In-memory imitation of /api/github/repositories/{id}/pull-requests*.
 * Pull requests appear only after a sync, like on the real server;
 * setNextPullRequestSync decides what the next sync brings.
 */

const state = {
  /** Synced pull requests per repository id. */
  stored: new Map<number, PullRequest[]>(),
  /** What the next sync of a repository returns. */
  pending: new Map<number, PullRequest[]>(),
  bodies: new Map<string, string>(),
  files: new Map<string, ChangedFile[]>(),
  failFiles: false,
}

export function resetPullRequestsMock() {
  state.stored.clear()
  state.pending.clear()
  state.bodies.clear()
  state.files.clear()
  state.failFiles = false
}

const key = (repositoryId: number, number: number) => `${repositoryId}#${number}`

export function pullRequest(number: number, overrides: Partial<PullRequest> = {}): PullRequest {
  return {
    id: 5000 + number,
    number,
    title: `Pull request ${number}`,
    state: 'OPEN',
    draft: false,
    authorLogin: 'octo-ada',
    sourceBranch: `feature-${number}`,
    targetBranch: 'main',
    headSha: String(number).padEnd(40, '0'),
    htmlUrl: `https://github.com/ada/backend/pull/${number}`,
    openedAt: '2026-10-20T10:00:00Z',
    updatedAt: '2026-10-25T10:00:00Z',
    ...overrides,
  }
}

export function changedFile(filename: string, overrides: Partial<ChangedFile> = {}): ChangedFile {
  return { filename, status: 'modified', additions: 1, deletions: 1, ...overrides }
}

/** Pull requests already synced (also marks the repository as synced). */
export function setSyncedPullRequests(repositoryId: number, pullRequests: PullRequest[]) {
  state.stored.set(repositoryId, pullRequests)
  const repo = storedRepository(repositoryId)
  if (repo) repo.pullRequestsSyncedAt = new Date(Date.now() - 2 * 60_000).toISOString()
}

export function setNextPullRequestSync(repositoryId: number, pullRequests: PullRequest[]) {
  state.pending.set(repositoryId, pullRequests)
}

export function setPullRequestDetails(
  repositoryId: number,
  number: number,
  details: { body?: string; files?: ChangedFile[] },
) {
  if (details.body !== undefined) state.bodies.set(key(repositoryId, number), details.body)
  if (details.files) state.files.set(key(repositoryId, number), details.files)
}

export function failPullRequestFiles() {
  state.failFiles = true
}

const BASE = `${API_BASE_URL}/api/github/repositories/:repositoryId/pull-requests`
const unauthorized = () => error(401, 'Access token has expired', '/api/github/repositories')

function find(repositoryId: number, number: number) {
  return state.stored.get(repositoryId)?.find((pr) => pr.number === number)
}

export const pullRequestsHandlers = [
  http.get(BASE, ({ request, params }) => {
    if (!authenticated(request)) return unauthorized()
    const repositoryId = Number(params.repositoryId)
    const url = new URL(request.url)
    const q = url.searchParams.get('q')?.toLowerCase().trim() ?? ''
    const stateFilter = url.searchParams.get('state')
    const page = Number(url.searchParams.get('page') ?? 0)
    const size = Number(url.searchParams.get('size') ?? 20)
    const number = /^#?(\d+)$/.exec(q)?.[1]

    const items = (state.stored.get(repositoryId) ?? []).filter(
      (pr) =>
        (!stateFilter || pr.state === stateFilter) &&
        (!q ||
          pr.title.toLowerCase().includes(q) ||
          pr.authorLogin.toLowerCase().includes(q) ||
          String(pr.number) === number),
    )
    return HttpResponse.json({
      content: items.slice(page * size, page * size + size),
      page,
      size,
      totalElements: items.length,
      totalPages: Math.ceil(items.length / size),
    })
  }),

  http.post(`${BASE}/sync`, ({ request, params }) => {
    if (!authenticated(request)) return unauthorized()
    const repositoryId = Number(params.repositoryId)
    const incoming = state.pending.get(repositoryId) ?? []
    state.pending.delete(repositoryId)
    const byNumber = new Map((state.stored.get(repositoryId) ?? []).map((pr) => [pr.number, pr]))
    incoming.forEach((pr) => byNumber.set(pr.number, pr))
    state.stored.set(repositoryId, [...byNumber.values()])

    const syncedAt = new Date().toISOString()
    const repo = storedRepository(repositoryId)
    if (repo) repo.pullRequestsSyncedAt = syncedAt
    return HttpResponse.json({ updated: incoming.length, total: byNumber.size, syncedAt })
  }),

  http.get(`${BASE}/:number`, ({ request, params }) => {
    if (!authenticated(request)) return unauthorized()
    const repositoryId = Number(params.repositoryId)
    const pr = find(repositoryId, Number(params.number))
    if (!pr) return error(404, 'Pull request not found', new URL(request.url).pathname)
    return HttpResponse.json({
      pullRequest: pr,
      body: state.bodies.get(key(repositoryId, pr.number)),
    })
  }),

  http.get(`${BASE}/:number/files`, ({ request, params }) => {
    if (!authenticated(request)) return unauthorized()
    const repositoryId = Number(params.repositoryId)
    const pr = find(repositoryId, Number(params.number))
    if (!pr) return error(404, 'Pull request not found', new URL(request.url).pathname)
    if (state.failFiles) {
      return error(
        502,
        "GitHub didn't respond as expected. Please try again in a moment.",
        new URL(request.url).pathname,
      )
    }
    return HttpResponse.json({
      headSha: pr.headSha,
      files: state.files.get(key(repositoryId, pr.number)) ?? [],
      truncated: false,
    })
  }),
]
