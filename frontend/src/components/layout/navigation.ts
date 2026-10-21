import {
  FlaskConicalIcon,
  FolderGit2Icon,
  LayoutDashboardIcon,
  type LucideIcon,
  SearchIcon,
  SettingsIcon,
  ShieldCheckIcon,
} from 'lucide-react'

export type NavItem = {
  to: string
  label: string
  icon: LucideIcon
  /** Build-plan level that delivers the page; shown on its placeholder. */
  level: number
  description: string
}

export const NAV_ITEMS: NavItem[] = [
  {
    to: '/dashboard',
    label: 'Dashboard',
    icon: LayoutDashboardIcon,
    level: 8,
    description: 'Review stats, severity breakdown, recent reviews and AI usage.',
  },
  {
    to: '/repositories',
    label: 'Repositories',
    icon: FolderGit2Icon,
    level: 5,
    description: 'Your GitHub repositories, with AI review switched on per repo.',
  },
  {
    to: '/reviews',
    label: 'Reviews',
    icon: ShieldCheckIcon,
    level: 7,
    description: 'AI review history and findings for your pull requests.',
  },
  {
    to: '/search',
    label: 'Code search',
    icon: SearchIcon,
    level: 10,
    description: 'Search your indexed repositories by meaning, not just keywords.',
  },
  {
    to: '/playground',
    label: 'Playground',
    icon: FlaskConicalIcon,
    level: 8,
    description: 'Paste a snippet and get an instant AI review.',
  },
  {
    to: '/settings',
    label: 'Settings',
    icon: SettingsIcon,
    level: 3,
    description: 'Profile, password, GitHub connection and review preferences.',
  },
]
