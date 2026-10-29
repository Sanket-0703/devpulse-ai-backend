import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef } from 'react'
import { toast } from 'sonner'

import { errorMessage } from '@/lib/api/errors'

import {
  getRepository,
  getSyncStatus,
  listRepositories,
  type Repository,
  type RepositoryListParams,
  type RepositoryPage,
  repositoryKeys,
  setReviewEnabled,
  startSync,
} from './api'

/** While a sync runs, the status is polled; when it finishes, the lists refresh. */
export function useSyncStatus() {
  const queryClient = useQueryClient()
  const query = useQuery({
    queryKey: repositoryKeys.sync,
    queryFn: getSyncStatus,
    refetchInterval: (q) => (q.state.data?.status === 'RUNNING' ? 1500 : false),
  })

  const previous = useRef(query.data?.status)
  useEffect(() => {
    const status = query.data?.status
    if (previous.current === 'RUNNING' && status && status !== 'RUNNING') {
      void queryClient.invalidateQueries({ queryKey: repositoryKeys.all })
      if (status === 'SUCCEEDED') toast.success('Repositories synced')
    }
    previous.current = status
  }, [query.data?.status, queryClient])

  return query
}

export function useStartSync() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: startSync,
    onSuccess: (status) => queryClient.setQueryData(repositoryKeys.sync, status),
    onError: (error) => toast.error(errorMessage(error)),
  })
}

export function useRepositories(params: RepositoryListParams, enabled: boolean) {
  return useQuery({
    queryKey: repositoryKeys.list(params),
    queryFn: () => listRepositories(params),
    enabled,
    // Keep showing the current page while the next one loads (no flicker).
    placeholderData: keepPreviousData,
  })
}

export function useRepository(id: number) {
  return useQuery({
    queryKey: repositoryKeys.detail(id),
    queryFn: () => getRepository(id),
    retry: false,
  })
}

/**
 * Flips "AI review" with an optimistic update: the switch moves instantly,
 * and snaps back with an error toast if the server refuses.
 */
export function useToggleReview() {
  const queryClient = useQueryClient()

  const applyEverywhere = (id: number, reviewEnabled: boolean) => {
    queryClient.setQueriesData<RepositoryPage>({ queryKey: ['repositories', 'list'] }, (page) =>
      page
        ? {
            ...page,
            content: page.content.map((r) => (r.id === id ? { ...r, reviewEnabled } : r)),
          }
        : page,
    )
    queryClient.setQueryData<Repository>(repositoryKeys.detail(id), (repo) =>
      repo ? { ...repo, reviewEnabled } : repo,
    )
  }

  return useMutation({
    mutationFn: ({ id, reviewEnabled }: { id: number; reviewEnabled: boolean }) =>
      setReviewEnabled(id, reviewEnabled),
    onMutate: async ({ id, reviewEnabled }) => {
      // Stop in-flight refetches from overwriting the optimistic value.
      await queryClient.cancelQueries({ queryKey: repositoryKeys.all })
      applyEverywhere(id, reviewEnabled)
    },
    onError: (error, { id, reviewEnabled }) => {
      applyEverywhere(id, !reviewEnabled)
      toast.error(errorMessage(error))
    },
    // Lists filtered by "review on/off" may change membership: refetch them.
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['repositories', 'list'] }),
  })
}
