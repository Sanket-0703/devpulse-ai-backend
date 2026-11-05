import { http, HttpResponse } from 'msw'

import { API_BASE_URL } from '@/lib/api/config'
import type { components } from '@/lib/api/schema'

import { authenticated, error } from './authMock'

type Dashboard = components['schemas']['DashboardResponse']

/** GET /api/dashboard; a brand-new account unless a test calls setDashboard(). */
function empty(): Dashboard {
  return {
    onboarding: {
      githubConnected: false,
      appInstalled: false,
      repositoriesSynced: false,
      reviewEnabled: false,
      firstReviewDone: false,
    },
    stats: {
      repositories: 0,
      reviewEnabledRepositories: 0,
      openPullRequests: 0,
      reviewsLast30Days: 0,
      reviewsTotal: 0,
    },
    openFindings: { critical: 0, high: 0, medium: 0, low: 0 },
    recentReviews: [],
    usage: {
      provider: 'mock',
      model: 'mock-reviewer',
      reviewsToday: 0,
      dailyReviewLimit: 20,
      playgroundRunsToday: 0,
      dailyPlaygroundLimit: 30,
      costTodayUsd: 0,
      dailyCostLimitUsd: 1,
      reviewsThisMonth: 0,
      tokensThisMonth: 0,
      costThisMonthUsd: 0,
    },
  }
}

let dashboard = empty()

export function resetDashboardMock() {
  dashboard = empty()
}

export function setDashboard(changes: Partial<Dashboard>) {
  dashboard = { ...dashboard, ...changes }
}

export const dashboardHandlers = [
  http.get(`${API_BASE_URL}/api/dashboard`, ({ request }) =>
    authenticated(request)
      ? HttpResponse.json(dashboard)
      : error(401, 'Access token has expired', '/api/dashboard'),
  ),
]
