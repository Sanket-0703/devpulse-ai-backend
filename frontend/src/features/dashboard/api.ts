import { useQuery } from '@tanstack/react-query'

import { api } from '@/lib/api/client'
import { unwrap } from '@/lib/api/errors'
import type { components } from '@/lib/api/schema'

export type Dashboard = components['schemas']['DashboardResponse']
export type Onboarding = Dashboard['onboarding']

export async function getDashboard(): Promise<Dashboard> {
  return unwrap(await api.GET('/api/dashboard'))
}

/** Always refetched when the page opens: it summarizes things that change elsewhere. */
export function useDashboard() {
  return useQuery({ queryKey: ['dashboard'], queryFn: getDashboard, staleTime: 0 })
}
