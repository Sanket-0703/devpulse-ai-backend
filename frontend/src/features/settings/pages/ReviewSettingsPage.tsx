import { CircleAlertIcon, RefreshCwIcon, WebhookIcon } from 'lucide-react'

import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import { SEVERITIES, type Severity } from '@/features/reviews/api'
import { timeAgo } from '@/lib/time'

import type { ReviewSettings, WebhookDelivery } from '../api'
import {
  useReviewSettings,
  useSaveReviewSettings,
  useWebhookDeliveries,
} from '../reviewSettingsHooks'

const SEVERITY_LABEL: Record<Severity, string> = {
  CRITICAL: 'Critical only',
  HIGH: 'High and above',
  MEDIUM: 'Medium and above',
  LOW: 'Everything (low and above)',
}

/** Settings → Reviews: automatic reviews, publishing to GitHub, and the webhook log. */
export function ReviewSettingsPage() {
  const settings = useReviewSettings()

  return (
    <div className="space-y-6">
      {settings.isPending ? (
        <Skeleton className="h-48 w-full" />
      ) : settings.isError ? (
        <Alert variant="destructive">
          <CircleAlertIcon />
          <AlertDescription>Couldn&apos;t load your review settings.</AlertDescription>
        </Alert>
      ) : (
        <PreferencesCard settings={settings.data} />
      )}
      <WebhooksCard configured={settings.data?.webhooksConfigured} />
    </div>
  )
}

function PreferencesCard({ settings }: { settings: ReviewSettings }) {
  const save = useSaveReviewSettings()
  const change = (changes: Partial<ReviewSettings>) => save.mutate({ ...settings, ...changes })

  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2>Review preferences</h2>
        </CardTitle>
        <CardDescription>
          Automatic reviews run when GitHub tells DevPulse a pull request opened or got new commits,
          for repositories with AI review on.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-5">
        <Setting
          id="auto-review"
          label="Review pull requests automatically"
          help="When a pull request opens or gets new commits. Drafts are skipped."
        >
          <Switch
            id="auto-review"
            checked={settings.autoReview}
            disabled={save.isPending}
            onCheckedChange={(autoReview) => change({ autoReview })}
          />
        </Setting>
        <Setting
          id="auto-publish"
          label="Publish automatic reviews to GitHub"
          help="Posts the findings on the pull request as review comments, without asking. Manual reviews always ask first."
        >
          <Switch
            id="auto-publish"
            checked={settings.autoPublish}
            disabled={save.isPending}
            onCheckedChange={(autoPublish) => change({ autoPublish })}
          />
        </Setting>
        <Setting
          id="min-severity"
          label="What to publish"
          help="Findings below this stay in DevPulse, so a pull request isn't buried in small remarks."
        >
          <select
            id="min-severity"
            className="border-input bg-background h-9 rounded-md border px-2 text-sm"
            value={settings.minPublishSeverity}
            disabled={save.isPending}
            onChange={(e) => change({ minPublishSeverity: e.target.value as Severity })}
          >
            {SEVERITIES.map((severity) => (
              <option key={severity} value={severity}>
                {SEVERITY_LABEL[severity]}
              </option>
            ))}
          </select>
        </Setting>
      </CardContent>
    </Card>
  )
}

function Setting({
  id,
  label,
  help,
  children,
}: {
  id: string
  label: string
  help: string
  children: React.ReactNode
}) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div className="min-w-0 flex-1 space-y-0.5">
        <label htmlFor={id} className="text-sm font-medium">
          {label}
        </label>
        <p className="text-muted-foreground text-sm">{help}</p>
      </div>
      {children}
    </div>
  )
}

const STATUS_VARIANT: Record<WebhookDelivery['status'], 'secondary' | 'outline' | 'destructive'> = {
  RECEIVED: 'secondary',
  PROCESSED: 'outline',
  IGNORED: 'secondary',
  FAILED: 'destructive',
}

function WebhooksCard({ configured }: { configured?: boolean }) {
  const deliveries = useWebhookDeliveries()

  return (
    <Card>
      <CardHeader className="flex flex-row items-start justify-between gap-3">
        <div className="space-y-1.5">
          <CardTitle>
            <h2>GitHub webhooks</h2>
          </CardTitle>
          <CardDescription>
            The latest events GitHub sent about your installation, and what DevPulse did with them.
          </CardDescription>
        </div>
        <Button
          variant="outline"
          size="sm"
          onClick={() => void deliveries.refetch()}
          disabled={deliveries.isFetching}
        >
          <RefreshCwIcon className={deliveries.isFetching ? 'animate-spin' : undefined} />
          Refresh
        </Button>
      </CardHeader>
      <CardContent className="space-y-4">
        {configured === false && (
          <Alert>
            <CircleAlertIcon />
            <AlertDescription>
              Webhooks aren&apos;t set up on this server yet: set GITHUB_WEBHOOK_SECRET and point
              the GitHub App&apos;s webhook at DevPulse (see docs/WEBHOOKS_LOCAL.md).
            </AlertDescription>
          </Alert>
        )}
        {deliveries.data?.length === 0 && (
          <p className="text-muted-foreground flex items-center gap-2 text-sm">
            <WebhookIcon className="size-4" aria-hidden />
            No deliveries yet. Push a commit to a pull request in one of your repositories.
          </p>
        )}
        {deliveries.data && deliveries.data.length > 0 && (
          <ul className="divide-y" aria-label="Webhook deliveries">
            {deliveries.data.map((delivery, i) => (
              <li key={i} className="flex flex-wrap items-start gap-x-3 gap-y-1 py-2.5 text-sm">
                <span className="font-mono">
                  {delivery.event}
                  {delivery.action && `.${delivery.action}`}
                </span>
                <Badge variant={STATUS_VARIANT[delivery.status]} className="capitalize">
                  {delivery.status.toLowerCase()}
                </Badge>
                <span className="text-muted-foreground ml-auto text-xs">
                  {timeAgo(delivery.receivedAt)}
                </span>
                {delivery.detail && (
                  <p className="text-muted-foreground w-full">{delivery.detail}</p>
                )}
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}
