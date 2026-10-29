import { useQuery } from '@tanstack/react-query'
import { Loader2Icon, SparklesIcon } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { errorMessage } from '@/lib/api/errors'

import { demoEnabled } from './api'
import { useAuth } from './authContext'

/**
 * "Try the demo": signs in to a seeded account with a sample repository and
 * a reviewed pull request. Shown only when the backend has demo mode on.
 */
export function DemoSignIn() {
  const { loginDemo } = useAuth()
  const [pending, setPending] = useState(false)
  const enabled = useQuery({ queryKey: ['auth', 'demo'], queryFn: demoEnabled, retry: false })

  if (!enabled.data) return null
  return (
    <Button
      type="button"
      variant="secondary"
      className="w-full"
      disabled={pending}
      onClick={async () => {
        setPending(true)
        try {
          await loginDemo() // <GuestOnly> then redirects into the app
        } catch (error) {
          toast.error(errorMessage(error))
          setPending(false)
        }
      }}
    >
      {pending ? <Loader2Icon className="animate-spin" /> : <SparklesIcon />}
      Try the demo (no GitHub needed)
    </Button>
  )
}
