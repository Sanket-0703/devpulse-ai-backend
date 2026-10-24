import { NavLink, Outlet } from 'react-router'

import { PageHeader } from '@/components/common/PageHeader'
import { cn } from '@/lib/utils'

// Later levels add GitHub (Level 4) and Review preferences (Level 9).
const SECTIONS = [
  { to: '/settings/profile', label: 'Profile' },
  { to: '/settings/security', label: 'Security' },
]

/** Settings page frame: title, section tabs, and the section in <Outlet />. */
export function SettingsLayout() {
  return (
    <>
      <PageHeader title="Settings" description="Your account, password and sessions." />

      <nav aria-label="Settings sections" className="mb-6 flex gap-1 border-b">
        {SECTIONS.map(({ to, label }) => (
          <NavLink
            key={to}
            to={to}
            className={({ isActive }) =>
              cn(
                '-mb-px border-b-2 px-3 py-2 text-sm font-medium transition-colors',
                'focus-visible:ring-ring rounded-t-md outline-none focus-visible:ring-2',
                isActive
                  ? 'border-primary text-foreground'
                  : 'text-muted-foreground hover:text-foreground border-transparent',
              )
            }
          >
            {label}
          </NavLink>
        ))}
      </nav>

      <div className="grid max-w-2xl gap-6">
        <Outlet />
      </div>
    </>
  )
}
