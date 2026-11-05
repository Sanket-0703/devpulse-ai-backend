import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'

import { errorMessage } from '@/lib/api/errors'

import {
  getReview,
  getUsage,
  isActive,
  listPullRequestReviews,
  listReviews,
  publishReview,
  type Review,
  type ReviewDetail,
  reviewKeys,
  setFindingDismissed,
  startReview,
} from './api'

const POLL_MS = 1500

/** A pull request's reviews; polled while the newest one is still running. */
export function usePullRequestReviews(repositoryId: number, number: number) {
  return useQuery({
    queryKey: reviewKeys.forPullRequest(repositoryId, number),
    queryFn: () => listPullRequestReviews(repositoryId, number),
    refetchInterval: (q) => (q.state.data?.[0] && isActive(q.state.data[0]) ? POLL_MS : false),
  })
}

/** One review with its findings; polled until it finishes. */
export function useReview(id: number | undefined) {
  const queryClient = useQueryClient()
  return useQuery({
    queryKey: reviewKeys.detail(id ?? 0),
    queryFn: async () => {
      const detail = await getReview(id!)
      // Finished: the history list and usage figures are stale now.
      if (!isActive(detail.review)) {
        void queryClient.invalidateQueries({ queryKey: ['reviews', 'pull-request'] })
        void queryClient.invalidateQueries({ queryKey: reviewKeys.usage })
      }
      return detail
    },
    enabled: id !== undefined,
    refetchInterval: (q) => (q.state.data && isActive(q.state.data.review) ? POLL_MS : false),
  })
}

export function useStartReview(repositoryId: number, number: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => startReview(repositoryId, number),
    onSuccess: (review) => {
      queryClient.setQueryData<Review[]>(reviewKeys.forPullRequest(repositoryId, number), (old) => [
        review,
        ...(old ?? []),
      ])
      void queryClient.invalidateQueries({ queryKey: reviewKeys.usage })
    },
    // 409 (already running) and 429 (daily limit) explain themselves.
    onError: (error) => toast.error(errorMessage(error)),
  })
}

/** Dismiss or restore a finding; the server's answer replaces the cached review. */
export function useDismissFinding(reviewId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ findingId, dismissed }: { findingId: number; dismissed: boolean }) =>
      setFindingDismissed(reviewId, findingId, dismissed),
    onSuccess: (detail: ReviewDetail) => {
      queryClient.setQueryData(reviewKeys.detail(reviewId), detail)
      void queryClient.invalidateQueries({ queryKey: ['reviews', 'pull-request'] })
      void queryClient.invalidateQueries({ queryKey: ['reviews', 'list'] })
    },
    onError: (error) => toast.error(errorMessage(error)),
  })
}

/** Publishing changes the review (published at, link): refresh it and the lists. */
export function usePublishReview(reviewId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => publishReview(reviewId),
    onSuccess: (review) => {
      queryClient.setQueryData<ReviewDetail>(reviewKeys.detail(reviewId), (detail) =>
        detail ? { ...detail, review } : detail,
      )
      void queryClient.invalidateQueries({ queryKey: ['reviews', 'pull-request'] })
      toast.success(
        review.publishedComments === 0
          ? 'Published to GitHub'
          : `Published to GitHub with ${review.publishedComments} comment${review.publishedComments === 1 ? '' : 's'}`,
      )
    },
    onError: (error) => toast.error(errorMessage(error)),
  })
}

export function useReviews(page: number) {
  return useQuery({
    queryKey: reviewKeys.list(page),
    queryFn: () => listReviews(page),
    placeholderData: keepPreviousData,
  })
}

export function useUsage() {
  return useQuery({ queryKey: reviewKeys.usage, queryFn: getUsage })
}
