import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'

import { API_BASE_URL } from '@/lib/api/config'

import { authHandlers } from './authMock'
import { dashboardHandlers } from './dashboardMock'
import { playgroundHandlers } from './playgroundMock'
import { pullRequestsHandlers } from './pullRequestsMock'
import { repositoriesHandlers } from './repositoriesMock'
import { reviewsHandlers } from './reviewsMock'

/** Default API mocks. Override per test with `server.use(...)`. */
export const handlers = [
  http.get(`${API_BASE_URL}/actuator/health`, () => HttpResponse.json({ status: 'UP' })),
  ...authHandlers,
  ...repositoriesHandlers,
  ...pullRequestsHandlers,
  ...reviewsHandlers,
  ...dashboardHandlers,
  ...playgroundHandlers,
]

export const server = setupServer(...handlers)
