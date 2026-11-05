import { http, HttpResponse } from 'msw'

import { API_BASE_URL } from '@/lib/api/config'
import type { components } from '@/lib/api/schema'

import { authenticated, error } from './authMock'

type Review = components['schemas']['ReviewResponse']
type Finding = components['schemas']['ReviewFindingResponse']
type Detail = components['schemas']['ReviewDetailResponse']

/**
 * In-memory imitation of the review endpoints. A started review reports
 * RUNNING once, then finishes with what setNextReviewResult() said
 * (like the background job on the real server).
 */

type Settings = components['schemas']['ReviewSettingsResponse']
type Delivery = components['schemas']['WebhookDeliveryResponse']

const DEFAULT_SETTINGS: Settings = {
  autoReview: true,
  autoPublish: false,
  minPublishSeverity: 'MEDIUM',
  webhooksConfigured: true,
}

const state = {
  details: [] as Detail[],
  settings: { ...DEFAULT_SETTINGS },
  deliveries: [] as Delivery[],
  failNextPublish: false,
  next: { findings: [] as Finding[], error: undefined as string | undefined },
  nextId: 1,
}

export function resetReviewsMock() {
  state.details = []
  state.settings = { ...DEFAULT_SETTINGS }
  state.deliveries = []
  state.failNextPublish = false
  state.next = { findings: [], error: undefined }
  state.nextId = 1
}

let findingId = 1

export function finding(title: string, overrides: Partial<Finding> = {}): Finding {
  return {
    id: findingId++,
    source: 'AI',
    severity: 'MEDIUM',
    category: 'BUG',
    title,
    description: `${title}: description`,
    recommendation: `${title}: fix`,
    filePath: 'src/App.java',
    line: 1,
    dismissed: false,
    ...overrides,
  }
}

export function setReviewSettings(changes: Partial<Settings>) {
  state.settings = { ...state.settings, ...changes }
}

export function storedReviewSettings() {
  return state.settings
}

export function setWebhookDeliveries(deliveries: Delivery[]) {
  state.deliveries = deliveries
}

/** The next "Publish to GitHub" fails like a GitHub 422. */
export function failNextPublish() {
  state.failNextPublish = true
}

/** What the next started review finds (or why it fails). */
export function setNextReviewResult(result: { findings?: Finding[]; error?: string }) {
  state.next = { findings: result.findings ?? [], error: result.error }
}

/** A finished review already stored for a pull request. */
export function addFinishedReview(
  repositoryId: number,
  number: number,
  findings: Finding[],
  overrides: Partial<Review> = {},
) {
  const detail: Detail = {
    review: { ...newReview(repositoryId, number), status: 'SUCCEEDED', ...overrides },
    findings,
  }
  recount(detail)
  state.details.unshift(detail)
  return detail
}

function newReview(repositoryId: number, number: number): Review {
  return {
    id: state.nextId++,
    repositoryId,
    repositoryFullName: 'ada/backend',
    pullRequestNumber: number,
    pullRequestTitle: `Pull request ${number}`,
    headSha: 'abcdef1234567890',
    status: 'RUNNING',
    triggeredBy: 'MANUAL',
    provider: 'mock',
    model: 'mock-reviewer',
    filesReviewed: 2,
    filesSkipped: 1,
    chunks: 1,
    truncated: false,
    promptTokens: 900,
    completionTokens: 140,
    estimatedCostUsd: 0,
    openFindings: { critical: 0, high: 0, medium: 0, low: 0 },
    publishedComments: 0,
    createdAt: new Date().toISOString(),
  }
}

function recount(detail: Detail) {
  const open = detail.findings.filter((f) => !f.dismissed)
  const count = (severity: Finding['severity']) =>
    open.filter((f) => f.severity === severity).length
  detail.review.openFindings = {
    critical: count('CRITICAL'),
    high: count('HIGH'),
    medium: count('MEDIUM'),
    low: count('LOW'),
  }
}

const unauthorized = () => error(401, 'Access token has expired', '/api/reviews')
const PR_REVIEWS = `${API_BASE_URL}/api/github/repositories/:repositoryId/pull-requests/:number/reviews`

const RANK = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3 } as const

export const reviewsHandlers = [
  http.get(`${API_BASE_URL}/api/settings/reviews`, ({ request }) =>
    authenticated(request) ? HttpResponse.json(state.settings) : unauthorized(),
  ),

  http.put(`${API_BASE_URL}/api/settings/reviews`, async ({ request }) => {
    if (!authenticated(request)) return unauthorized()
    const body = (await request.json()) as Omit<Settings, 'webhooksConfigured'>
    state.settings = { ...state.settings, ...body }
    return HttpResponse.json(state.settings)
  }),

  http.get(`${API_BASE_URL}/api/github/webhooks/deliveries`, ({ request }) =>
    authenticated(request) ? HttpResponse.json(state.deliveries) : unauthorized(),
  ),

  http.post(`${API_BASE_URL}/api/reviews/:id/publish`, ({ request, params }) => {
    if (!authenticated(request)) return unauthorized()
    const detail = state.details.find((d) => d.review.id === Number(params.id))
    if (!detail) return error(404, 'Review not found', '/api/reviews')
    if (detail.review.publishedAt) {
      return error(409, 'This review is already on GitHub.', '/api/reviews')
    }
    if (state.failNextPublish) {
      state.failNextPublish = false
      return error(502, 'GitHub refused the request.', '/api/reviews')
    }
    const minimum = RANK[state.settings.minPublishSeverity]
    detail.review.publishedAt = new Date().toISOString()
    detail.review.githubReviewUrl = `https://github.com/ada/backend/pull/${detail.review.pullRequestNumber}#pullrequestreview-1`
    detail.review.publishedComments = detail.findings.filter(
      (f) => !f.dismissed && f.line != null && RANK[f.severity] <= minimum,
    ).length
    return HttpResponse.json(detail.review)
  }),

  http.post(PR_REVIEWS, ({ request, params }) => {
    if (!authenticated(request)) return unauthorized()
    const repositoryId = Number(params.repositoryId)
    const number = Number(params.number)
    const active = state.details.some(
      (d) =>
        d.review.repositoryId === repositoryId &&
        d.review.pullRequestNumber === number &&
        d.review.status === 'RUNNING',
    )
    if (active) {
      return error(409, 'A review of this pull request is already running.', '/api/reviews')
    }
    const detail: Detail = { review: newReview(repositoryId, number), findings: [] }
    state.details.unshift(detail)
    return HttpResponse.json(detail.review, { status: 202 })
  }),

  http.get(PR_REVIEWS, ({ request, params }) => {
    if (!authenticated(request)) return unauthorized()
    return HttpResponse.json(
      state.details
        .filter(
          (d) =>
            d.review.repositoryId === Number(params.repositoryId) &&
            d.review.pullRequestNumber === Number(params.number),
        )
        .map((d) => d.review),
    )
  }),

  http.get(`${API_BASE_URL}/api/reviews/usage`, ({ request }) => {
    if (!authenticated(request)) return unauthorized()
    const tokens = state.details.reduce(
      (sum, d) => sum + d.review.promptTokens + d.review.completionTokens,
      0,
    )
    return HttpResponse.json({
      provider: 'mock',
      model: 'mock-reviewer',
      reviewsToday: state.details.length,
      dailyReviewLimit: 20,
      playgroundRunsToday: 0,
      dailyPlaygroundLimit: 30,
      costTodayUsd: 0,
      dailyCostLimitUsd: 1,
      reviewsThisMonth: state.details.length,
      tokensThisMonth: tokens,
      costThisMonthUsd: 0,
    })
  }),

  http.get(`${API_BASE_URL}/api/reviews`, ({ request }) => {
    if (!authenticated(request)) return unauthorized()
    const page = Number(new URL(request.url).searchParams.get('page') ?? 0)
    const size = 20
    const items = state.details.map((d) => d.review)
    return HttpResponse.json({
      content: items.slice(page * size, page * size + size),
      page,
      size,
      totalElements: items.length,
      totalPages: Math.ceil(items.length / size),
    })
  }),

  http.get(`${API_BASE_URL}/api/reviews/:id`, ({ request, params }) => {
    if (!authenticated(request)) return unauthorized()
    const detail = state.details.find((d) => d.review.id === Number(params.id))
    if (!detail) return error(404, 'Review not found', '/api/reviews')
    const response = structuredClone(detail)
    if (detail.review.status === 'RUNNING') {
      // Finish now: the next poll sees the result.
      if (state.next.error) {
        detail.review.status = 'FAILED'
        detail.review.error = state.next.error
      } else {
        detail.review.status = 'SUCCEEDED'
        detail.findings = state.next.findings
        recount(detail)
      }
      detail.review.finishedAt = new Date().toISOString()
    }
    return HttpResponse.json(response)
  }),

  http.patch(`${API_BASE_URL}/api/reviews/:id/findings/:findingId`, async ({ request, params }) => {
    if (!authenticated(request)) return unauthorized()
    const detail = state.details.find((d) => d.review.id === Number(params.id))
    const target = detail?.findings.find((f) => f.id === Number(params.findingId))
    if (!detail || !target) return error(404, 'Finding not found', '/api/reviews')
    target.dismissed = ((await request.json()) as { dismissed: boolean }).dismissed
    recount(detail)
    return HttpResponse.json(detail)
  }),
]
