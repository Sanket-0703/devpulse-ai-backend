import { useQuery } from '@tanstack/react-query'

import { api } from '@/lib/api/client'
import { unwrap } from '@/lib/api/errors'
import type { components } from '@/lib/api/schema'

export type SearchResult = components['schemas']['CodeSearchResult']

export async function searchCode(query: string, repositoryId?: number): Promise<SearchResult[]> {
  return unwrap(
    await api.GET('/api/search', { params: { query: { q: query, repositoryId, limit: 10 } } }),
  )
}

/** Runs only for a non-empty query; the same query is answered from the cache. */
export function useCodeSearch(query: string, repositoryId?: number) {
  return useQuery({
    queryKey: ['search', query, repositoryId ?? 'all'],
    queryFn: () => searchCode(query, repositoryId),
    enabled: query.trim().length > 0,
    staleTime: 60_000,
    retry: false,
  })
}
