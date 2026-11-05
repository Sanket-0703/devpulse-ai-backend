import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'

import { errorMessage } from '@/lib/api/errors'

import {
  getReviewSettings,
  listWebhookDeliveries,
  type ReviewSettings,
  reviewSettingsKey,
  saveReviewSettings,
  webhookDeliveriesKey,
} from './api'

export function useReviewSettings() {
  return useQuery({ queryKey: reviewSettingsKey, queryFn: getReviewSettings })
}

/** Saves on every change (like the repository review switch); the answer replaces the cache. */
export function useSaveReviewSettings() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (settings: ReviewSettings) =>
      saveReviewSettings({
        autoReview: settings.autoReview,
        autoPublish: settings.autoPublish,
        minPublishSeverity: settings.minPublishSeverity,
      }),
    onSuccess: (saved) => {
      queryClient.setQueryData(reviewSettingsKey, saved)
      toast.success('Review settings saved')
    },
    onError: (error) => toast.error(errorMessage(error)),
  })
}

export function useWebhookDeliveries() {
  return useQuery({ queryKey: webhookDeliveriesKey, queryFn: listWebhookDeliveries })
}
