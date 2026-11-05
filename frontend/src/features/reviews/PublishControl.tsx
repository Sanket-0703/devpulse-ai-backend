import { ExternalLinkIcon, Loader2Icon, SendIcon } from 'lucide-react'

import { GitHubIcon } from '@/components/common/GitHubIcon'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { useReviewSettings } from '@/features/settings/reviewSettingsHooks'
import { timeAgo } from '@/lib/time'

import type { Review } from './api'
import { usePublishReview } from './hooks'

const MINIMUM: Record<string, string> = {
  CRITICAL: 'critical',
  HIGH: 'high or critical',
  MEDIUM: 'medium severity or above',
  LOW: 'any severity',
}

/**
 * "Publish to GitHub", behind a confirmation: it posts on the real pull
 * request, visible to everyone with access. Once published, a link to it.
 */
export function PublishControl({
  review,
  pullRequestNumber,
}: {
  review: Review
  pullRequestNumber: number
}) {
  const publish = usePublishReview(review.id)
  const settings = useReviewSettings()

  if (review.publishedAt) {
    return (
      <p className="flex flex-wrap items-center gap-2 text-sm">
        <GitHubIcon className="size-4" />
        Published to GitHub {timeAgo(review.publishedAt)} · {review.publishedComments}{' '}
        {review.publishedComments === 1 ? 'comment' : 'comments'}
        {review.githubReviewUrl && (
          <a
            href={review.githubReviewUrl}
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-1 underline underline-offset-4"
          >
            View on GitHub <ExternalLinkIcon className="size-3" />
          </a>
        )}
      </p>
    )
  }

  const minimum = settings.data
    ? MINIMUM[settings.data.minPublishSeverity]
    : 'medium severity or above'
  return (
    <AlertDialog>
      <AlertDialogTrigger asChild>
        <Button variant="outline" size="sm" disabled={publish.isPending}>
          {publish.isPending ? <Loader2Icon className="animate-spin" /> : <SendIcon />}
          Publish to GitHub
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Publish this review to GitHub?</AlertDialogTitle>
          <AlertDialogDescription>
            DevPulse will post a review on pull request #{pullRequestNumber}, with an inline comment
            for each open finding of {minimum} (Settings → Reviews). Everyone who can see the pull
            request will see it.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction onClick={() => publish.mutate()}>Publish</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
