import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'

import type { Repository } from '@/features/repositories/api'
import { repositoryKeys } from '@/features/repositories/api'
import { errorMessage } from '@/lib/api/errors'

import {
  getPullRequest,
  getPullRequestFiles,
  listPullRequests,
  type PullRequestListParams,
  pullRequestKeys,
  syncPullRequests,
} from './api'

export function usePullRequests(repositoryId: number, params: PullRequestListParams) {
  return useQuery({
    queryKey: pullRequestKeys.list(repositoryId, params),
    queryFn: () => listPullRequests(repositoryId, params),
    // Keep showing the current page while the next one loads (no flicker).
    placeholderData: keepPreviousData,
  })
}

export function usePullRequest(repositoryId: number, number: number) {
  return useQuery({
    queryKey: pullRequestKeys.detail(repositoryId, number),
    queryFn: () => getPullRequest(repositoryId, number),
    retry: false,
  })
}

export function usePullRequestFiles(repositoryId: number, number: number) {
  return useQuery({
    queryKey: pullRequestKeys.files(repositoryId, number),
    queryFn: () => getPullRequestFiles(repositoryId, number),
    // A diff only changes with a new commit (and a sync); don't refetch on every focus.
    staleTime: 5 * 60_000,
    retry: false,
  })
}

/** Syncs, then refreshes the lists and the repository's "synced … ago". */
export function useSyncPullRequests(repositoryId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => syncPullRequests(repositoryId),
    onSuccess: (result) => {
      queryClient.setQueryData<Repository>(repositoryKeys.detail(repositoryId), (repo) =>
        repo ? { ...repo, pullRequestsSyncedAt: result.syncedAt } : repo,
      )
      void queryClient.invalidateQueries({ queryKey: pullRequestKeys.repository(repositoryId) })
      toast.success(
        result.updated === 0
          ? 'Pull requests are up to date'
          : `Synced ${result.updated} pull request${result.updated === 1 ? '' : 's'}`,
      )
    },
    onError: (error) => toast.error(errorMessage(error)),
  })
}
