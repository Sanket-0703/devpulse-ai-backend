import { onSessionEnded, refreshSession, setAccessToken } from '@/features/auth/session'
import { endServerSession, expireAccessTokens, refreshCallCount } from '@/test/authMock'

import { api } from './client'

/** Signs in the way the app does on start-up: refresh from the cookie. */
async function startSession() {
  const session = await refreshSession()
  expect(session).not.toBeNull()
}

describe('authFetch (typed API client)', () => {
  it('sends the access token', async () => {
    await startSession()

    const { data, response } = await api.GET('/api/auth/me')

    expect(response.status).toBe(200)
    expect(data?.email).toBe('ada@example.com')
  })

  it('refreshes an expired access token and retries the request once', async () => {
    await startSession()
    expireAccessTokens()

    const { data, response } = await api.GET('/api/auth/me')

    expect(response.status).toBe(200)
    expect(data?.email).toBe('ada@example.com')
    expect(refreshCallCount()).toBe(2) // start-up + the retry
  })

  it('shares one refresh between requests that fail at the same time', async () => {
    await startSession()
    expireAccessTokens()

    const results = await Promise.all([
      api.GET('/api/auth/me'),
      api.GET('/api/auth/me'),
      api.GET('/api/auth/me'),
    ])

    expect(results.map((r) => r.response.status)).toEqual([200, 200, 200])
    expect(refreshCallCount()).toBe(2) // start-up + ONE shared refresh
  })

  it('reports the end of the session when the refresh fails too', async () => {
    await startSession()
    endServerSession()
    const ended = vi.fn()
    const unsubscribe = onSessionEnded(ended)

    const { response } = await api.GET('/api/auth/me')

    expect(response.status).toBe(401)
    expect(ended).toHaveBeenCalledOnce()
    unsubscribe()
  })

  it('does not try to refresh when there was no token to begin with', async () => {
    setAccessToken(null)

    const { response } = await api.GET('/api/auth/me')

    expect(response.status).toBe(401)
    expect(refreshCallCount()).toBe(0)
  })
})
