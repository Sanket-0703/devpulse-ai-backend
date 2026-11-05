import { http, HttpResponse } from 'msw'

import { API_BASE_URL } from '@/lib/api/config'
import type { components } from '@/lib/api/schema'

import { authenticated, error } from './authMock'

type Finding = components['schemas']['PlaygroundFinding']

/**
 * POST /api/playground/review: a tiny imitation of the rules + mock AI.
 * SQL built with "+" is a rule finding, System.out an AI one.
 */
const state = { limited: false }

export function resetPlaygroundMock() {
  state.limited = false
}

/** The next run answers 429, like after five runs in a minute. */
export function limitPlayground() {
  state.limited = true
}

export const playgroundHandlers = [
  http.post(`${API_BASE_URL}/api/playground/review`, async ({ request }) => {
    if (!authenticated(request))
      return error(401, 'Access token has expired', '/api/playground/review')
    if (state.limited) {
      return error(429, 'Slow down: at most 5 Playground runs a minute.', '/api/playground/review')
    }
    const { code } = (await request.json()) as { code: string; language: string }
    if (!code.trim()) {
      return error(400, 'Paste some code to review', '/api/playground/review')
    }
    const findings: Finding[] = []
    code.split('\n').forEach((line, i) => {
      if (/SELECT.*["']\s*\+/.test(line)) {
        findings.push({
          source: 'RULE',
          ruleId: 'sql-concatenation',
          severity: 'HIGH',
          category: 'SECURITY',
          title: 'SQL built from strings',
          description: 'SQL injection risk.',
          recommendation: 'Use a parameterized query.',
          line: i + 1,
        })
      }
      if (line.includes('System.out')) {
        findings.push({
          source: 'AI',
          severity: 'LOW',
          category: 'CODE_QUALITY',
          title: 'Debug output left in',
          description: 'Printing bypasses logging.',
          recommendation: 'Use a logger.',
          line: i + 1,
        })
      }
    })
    return HttpResponse.json({
      findings,
      provider: 'mock',
      model: 'mock-reviewer',
      promptTokens: 700,
      completionTokens: 140,
      estimatedCostUsd: 0,
      truncated: false,
    })
  }),
]
