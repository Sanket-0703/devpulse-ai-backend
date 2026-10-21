import '@testing-library/jest-dom/vitest'

import { cleanup } from '@testing-library/react'

import { server } from './server'

// MSW intercepts fetch() so tests never hit the real backend.
// Unmocked requests fail the test, so a missing handler is obvious.
beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
afterEach(() => {
  cleanup()
  server.resetHandlers()
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
