import { http, HttpResponse } from 'msw'

import { API_BASE_URL } from '@/lib/api/config'
import type { components } from '@/lib/api/schema'

import { authenticated, error } from './authMock'

type Result = components['schemas']['CodeSearchResult']

/** GET /api/search: answers with setSearchResults(); records the last query. */
const state = { results: [] as Result[], lastQuery: null as URLSearchParams | null }

export function resetSearchMock() {
  state.results = []
  state.lastQuery = null
}

export function setSearchResults(results: Result[]) {
  state.results = results
}

export function lastSearch() {
  return state.lastQuery
}

export function searchResult(path: string, overrides: Partial<Result> = {}): Result {
  return {
    repositoryId: 1,
    repositoryFullName: 'ada/backend',
    path,
    startLine: 10,
    endLine: 12,
    language: 'java',
    content: 'public boolean isValid(String jwt) {\n  return verify(jwt);\n}',
    score: 0.71,
    url: `https://github.com/ada/backend/blob/c0ffee/${path}#L10-L12`,
    ...overrides,
  }
}

export const searchHandlers = [
  http.get(`${API_BASE_URL}/api/search`, ({ request }) => {
    if (!authenticated(request)) return error(401, 'Access token has expired', '/api/search')
    state.lastQuery = new URL(request.url).searchParams
    return HttpResponse.json(state.results)
  }),
]
