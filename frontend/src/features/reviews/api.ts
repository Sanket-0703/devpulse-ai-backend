import { api } from '@/lib/api/client'
import { unwrap } from '@/lib/api/errors'
import type { components } from '@/lib/api/schema'

export type Review = components['schemas']['ReviewResponse']
export type ReviewPage = components['schemas']['PageResponseReviewResponse']
export type ReviewDetail = components['schemas']['ReviewDetailResponse']
export type Finding = components['schemas']['ReviewFindingResponse']
export type Severity = Finding['severity']
export type AIUsage = components['schemas']['AIUsageResponse']

export const SEVERITIES: Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']

export const PAGE_SIZE = 20

export const reviewKeys = {
  all: ['reviews'] as const,
  list: (page: number) => ['reviews', 'list', page] as const,
  detail: (id: number) => ['reviews', 'detail', id] as const,
  forPullRequest: (repositoryId: number, number: number) =>
    ['reviews', 'pull-request', repositoryId, number] as const,
  usage: ['reviews', 'usage'] as const,
}

export const isActive = (review: Pick<Review, 'status'>) =>
  review.status === 'QUEUED' || review.status === 'RUNNING'

/** "$0.0027", or "free" for the mock provider. */
export function formatCost(review: Pick<Review, 'provider' | 'estimatedCostUsd'>): string {
  if (review.provider === 'mock') return 'free (mock AI)'
  return `$${review.estimatedCostUsd.toFixed(4)}`
}

export async function startReview(repositoryId: number, number: number): Promise<Review> {
  return unwrap(
    await api.POST('/api/github/repositories/{repositoryId}/pull-requests/{number}/reviews', {
      params: { path: { repositoryId, number } },
    }),
  )
}

export async function listPullRequestReviews(
  repositoryId: number,
  number: number,
): Promise<Review[]> {
  return unwrap(
    await api.GET('/api/github/repositories/{repositoryId}/pull-requests/{number}/reviews', {
      params: { path: { repositoryId, number } },
    }),
  )
}

export async function getReview(id: number): Promise<ReviewDetail> {
  return unwrap(await api.GET('/api/reviews/{id}', { params: { path: { id } } }))
}

export async function setFindingDismissed(
  reviewId: number,
  findingId: number,
  dismissed: boolean,
): Promise<ReviewDetail> {
  return unwrap(
    await api.PATCH('/api/reviews/{id}/findings/{findingId}', {
      params: { path: { id: reviewId, findingId } },
      body: { dismissed },
    }),
  )
}

export async function listReviews(page: number): Promise<ReviewPage> {
  return unwrap(await api.GET('/api/reviews', { params: { query: { page, size: PAGE_SIZE } } }))
}

export async function getUsage(): Promise<AIUsage> {
  return unwrap(await api.GET('/api/reviews/usage'))
}
