import { GitHubIcon } from '@/components/common/GitHubIcon'
import { Button } from '@/components/ui/button'

import { GITHUB_SIGN_IN_URL } from './api'

/** "Continue with GitHub" plus an "or" divider, for the sign-in and sign-up pages. */
export function GitHubSignInButton() {
  return (
    <>
      <Button asChild variant="outline" className="w-full">
        <a href={GITHUB_SIGN_IN_URL}>
          <GitHubIcon className="size-4" />
          Continue with GitHub
        </a>
      </Button>
      <div className="text-muted-foreground flex items-center gap-3 text-xs uppercase">
        <span className="bg-border h-px flex-1" />
        or
        <span className="bg-border h-px flex-1" />
      </div>
    </>
  )
}
