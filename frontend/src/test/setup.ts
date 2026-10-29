import '@testing-library/jest-dom/vitest'

import { cleanup } from '@testing-library/react'
import { toast } from 'sonner'

import { setAccessToken } from '@/features/auth/session'

import { resetAuthMock } from './authMock'
import { resetPullRequestsMock } from './pullRequestsMock'
import { resetRepositoriesMock } from './repositoriesMock'
import { resetReviewsMock } from './reviewsMock'
import { server } from './server'

// MSW intercepts fetch() so tests never hit the real backend.
// Unmocked requests fail the test, so a missing handler is obvious.
beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
// Every test starts signed in as DEMO_USER unless it calls resetAuthMock({ signedIn: false }).
beforeEach(() => {
  resetAuthMock()
  resetRepositoriesMock()
  resetPullRequestsMock()
  resetReviewsMock()
})
afterEach(() => {
  cleanup()
  server.resetHandlers()
  setAccessToken(null)
  localStorage.clear()
  // sonner keeps toasts in module state, which outlives each test's render.
  toast.dismiss()
})
afterAll(() => server.close())

// jsdom lacks matchMedia, which the theme provider uses for the "system" theme.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }),
})
