import { ActivityIcon } from 'lucide-react'
import { Link } from 'react-router'

export function Logo() {
  return (
    <Link to="/dashboard" className="flex items-center gap-2 font-semibold tracking-tight">
      <span className="bg-primary text-primary-foreground flex size-7 items-center justify-center rounded-md">
        <ActivityIcon className="size-4" aria-hidden />
      </span>
      DevPulse AI
    </Link>
  )
}
