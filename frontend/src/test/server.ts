import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'

import { API_BASE_URL } from '@/lib/api/client'

/** Default API mocks. Override per test with `server.use(...)`. */
export const handlers = [
  http.get(`${API_BASE_URL}/actuator/health`, () => HttpResponse.json({ status: 'UP' })),
]

export const server = setupServer(...handlers)
