import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  CheckCircle2Icon,
  CircleAlertIcon,
  ExternalLinkIcon,
  FolderGit2Icon,
  Loader2Icon,
  UnplugIcon,
} from 'lucide-react'
import { toast } from 'sonner'

import { GitHubIcon } from '@/components/common/GitHubIcon'
import { Alert, AlertDescription } from '@/components/ui/alert'
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
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { useCurrentUser } from '@/features/auth/authContext'
import {
  disconnect,
  fetchConnection,
  type GitHubConnection,
  githubConnectionKey,
  startConnect,
  startInstall,
} from '@/features/github/api'
import { errorMessage } from '@/lib/api/errors'
import { redirectTo } from '@/lib/browser'

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'long' })

export function GitHubSettingsPage() {
  const connection = useQuery({ queryKey: githubConnectionKey, queryFn: fetchConnection })

  if (connection.isPending) {
    return (
      <Card>
        <CardHeader>
          <Skeleton className="h-5 w-40" />
          <Skeleton className="h-4 w-64" />
        </CardHeader>
        <CardContent>
          <Skeleton className="h-10 w-full" />
        </CardContent>
      </Card>
    )
  }

  if (connection.isError) {
    return (
      <Alert variant="destructive">
        <CircleAlertIcon />
        <AlertDescription className="flex items-center justify-between gap-4">
          Couldn&apos;t load your GitHub connection. {errorMessage(connection.error)}
          <Button size="sm" variant="outline" onClick={() => void connection.refetch()}>
            Retry
          </Button>
        </AlertDescription>
      </Alert>
    )
  }

  return connection.data.connected ? (
    <ConnectedCards connection={connection.data} />
  ) : (
    <ConnectCard />
  )
}

/** Sends the browser to GitHub; the backend brings it back to /github/callback. */
function useGitHubRedirect(start: () => Promise<string>) {
  return useMutation({
    mutationFn: start,
    onSuccess: (url) => redirectTo(url),
    onError: (error) => toast.error(errorMessage(error)),
  })
}

function ConnectCard() {
  const connect = useGitHubRedirect(startConnect)

  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2>Connect GitHub</h2>
        </CardTitle>
        <CardDescription>
          DevPulse reviews pull requests on the GitHub repositories you choose. It can read code and
          comment on pull requests, nothing else, and you can disconnect at any time.
        </CardDescription>
      </CardHeader>
      <CardFooter className="justify-end">
        <Button onClick={() => connect.mutate()} disabled={connect.isPending}>
          {connect.isPending ? (
            <Loader2Icon className="animate-spin" />
          ) : (
            <GitHubIcon className="size-4" />
          )}
          Connect GitHub
        </Button>
      </CardFooter>
    </Card>
  )
}

function ConnectedCards({ connection }: { connection: GitHubConnection }) {
  const user = useCurrentUser()
  const queryClient = useQueryClient()
  const install = useGitHubRedirect(startInstall)

  const disconnectMutation = useMutation({
    mutationFn: disconnect,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: githubConnectionKey })
      toast.success('GitHub disconnected')
    },
    onError: (error) => toast.error(errorMessage(error)),
  })

  return (
    <>
      <Card>
        <CardHeader>
          <CardTitle>
            <h2>GitHub account</h2>
          </CardTitle>
          <CardDescription>
            Connected
            {connection.connectedAt &&
              ` since ${dateFormat.format(new Date(connection.connectedAt))}`}
          </CardDescription>
        </CardHeader>
        <CardContent className="flex items-center gap-3">
          <Avatar className="size-10">
            {connection.avatarUrl && <AvatarImage src={connection.avatarUrl} alt="" />}
            <AvatarFallback>
              <GitHubIcon className="size-5" />
            </AvatarFallback>
          </Avatar>
          <a
            href={connection.profileUrl ?? undefined}
            target="_blank"
            rel="noreferrer"
            className="font-medium underline-offset-4 hover:underline"
          >
            @{connection.username}
          </a>
        </CardContent>
        <CardFooter className="justify-end">
          <DisconnectButton
            canDisconnect={user.hasPassword}
            pending={disconnectMutation.isPending}
            onConfirm={() => disconnectMutation.mutate()}
          />
        </CardFooter>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>
            <h2>Repositories</h2>
          </CardTitle>
          <CardDescription>
            {connection.installed
              ? 'The DevPulse app is installed. You choose which repositories it can see on GitHub.'
              : 'Install the DevPulse app on GitHub and pick the repositories it should review.'}
          </CardDescription>
        </CardHeader>
        <CardContent>
          {connection.installed ? (
            <p className="flex items-center gap-2 text-sm">
              <CheckCircle2Icon className="size-4 text-emerald-600" aria-hidden />
              App installed
            </p>
          ) : (
            <p className="text-muted-foreground flex items-center gap-2 text-sm">
              <FolderGit2Icon className="size-4" aria-hidden />
              Not installed on any repository yet
            </p>
          )}
        </CardContent>
        <CardFooter className="justify-end">
          {connection.installed && connection.manageRepositoriesUrl ? (
            <Button asChild variant="outline">
              <a href={connection.manageRepositoriesUrl} target="_blank" rel="noreferrer">
                Manage repositories <ExternalLinkIcon />
              </a>
            </Button>
          ) : (
            <Button onClick={() => install.mutate()} disabled={install.isPending}>
              {install.isPending ? (
                <Loader2Icon className="animate-spin" />
              ) : (
                <GitHubIcon className="size-4" />
              )}
              Install on repositories
            </Button>
          )}
        </CardFooter>
      </Card>
    </>
  )
}

function DisconnectButton({
  canDisconnect,
  pending,
  onConfirm,
}: {
  canDisconnect: boolean
  pending: boolean
  onConfirm: () => void
}) {
  // Without a password, GitHub is the only way to sign in: disconnecting would lock the user out.
  if (!canDisconnect) {
    return (
      <p className="text-muted-foreground text-sm">
        To disconnect, first set a password in the Security tab.
      </p>
    )
  }

  return (
    <AlertDialog>
      <AlertDialogTrigger asChild>
        <Button variant="destructive" disabled={pending}>
          {pending ? <Loader2Icon className="animate-spin" /> : <UnplugIcon />}
          Disconnect
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Disconnect GitHub?</AlertDialogTitle>
          <AlertDialogDescription>
            DevPulse loses access to your GitHub account and its synced repositories are removed
            here. The app stays installed on GitHub until you uninstall it there.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction onClick={onConfirm}>Disconnect</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
