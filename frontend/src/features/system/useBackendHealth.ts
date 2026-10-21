import { useQuery } from '@tanstack/react-query'

import { API_BASE_URL } from '@/lib/api/client'

export type BackendStatus = 'online' | 'offline' | 'checking'

/**
 * Polls the backend's /actuator/health every 30 s. Actuator endpoints are not
 * part of the OpenAPI docs, so this uses plain fetch instead of the typed client.
 */
export function useBackendHealth(): BackendStatus {
  const { data, isPending, isError } = useQuery({
    queryKey: ['backend-health'],
    queryFn: async () => {
      const response = await fetch(`${API_BASE_URL}/actuator/health`)
      if (!response.ok) throw new Error(`Health check failed: ${response.status}`)
      return (await response.json()) as { status: string }
    },
    refetchInterval: 30_000,
    retry: false,
  })

  if (isPending) return 'checking'
  if (isError || data.status !== 'UP') return 'offline'
  return 'online'
}
