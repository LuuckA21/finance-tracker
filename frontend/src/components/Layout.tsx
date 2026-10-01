import { Suspense, useState } from 'react'
import { NavLink, Outlet } from 'react-router'
import {
  ArrowLeftRight,
  BarChart3,
  FileText,
  Flag,
  LayoutDashboard,
  LogOut,
  Menu,
  PiggyBank,
  RefreshCw,
  Repeat,
  Settings,
  Target,
  TrendingUp,
  Users,
  Wallet,
  X,
  type LucideIcon,
} from 'lucide-react'
import { useMe } from '../api/hooks'
import { useLogout } from '../auth/useLogout'
import { useI18n, type MessageKey } from '../i18n'
import { Spinner } from './ui'

/**
 * The menu in groups, each from recording to analysing: the overview; cash flow (entries,
 * recurring ones, budgets, income & expenses); net worth (positions, updating their values, goals,
 * net worth over time); the annual report across both; settings.
 */
const NAV_GROUPS = [
  [{ to: '/', label: 'nav.overview', icon: LayoutDashboard, end: true }],
  [
    { to: '/movimenti', label: 'nav.entries', icon: ArrowLeftRight },
    { to: '/ricorrenti', label: 'nav.recurring', icon: Repeat },
    { to: '/budget', label: 'nav.budget', icon: Target },
    { to: '/flussi', label: 'nav.cashflow', icon: BarChart3 },
    { to: '/previsione', label: 'nav.forecast', icon: TrendingUp },
  ],
  [
    { to: '/posizioni', label: 'nav.positions', icon: Wallet },
    { to: '/aggiorna', label: 'nav.bulkUpdate', icon: RefreshCw },
    { to: '/obiettivi', label: 'nav.goals', icon: Flag },
    { to: '/patrimonio', label: 'nav.netWorth', icon: PiggyBank },
  ],
  [{ to: '/riepilogo', label: 'nav.report', icon: FileText }],
  [{ to: '/impostazioni', label: 'nav.settings', icon: Settings }],
] as const

type NavItem = { to: string; label: MessageKey; icon: LucideIcon; end?: boolean }

export function Layout() {
  const me = useMe().data
  const logout = useLogout()
  const [open, setOpen] = useState(false)
  const { t } = useI18n()

  // Administrators also manage the users, next to the settings
  const groups: readonly (readonly NavItem[])[] = me?.role === 'ADMIN'
    ? [...NAV_GROUPS.slice(0, -1), [...NAV_GROUPS[NAV_GROUPS.length - 1], { to: '/admin/utenti', label: 'nav.users', icon: Users }]]
    : NAV_GROUPS

  const nav = (
    <nav className="flex flex-col gap-0.5" aria-label={t('nav.main')}>
      {groups.flatMap((group, index) => [
        ...(index > 0 ? [<hr key={`separator-${index}`} className="mx-3 my-1.5 border-line" aria-hidden />] : []),
        ...group.map(({ to, label, icon: Icon, end }) => (
          <NavLink
            key={to}
            to={to}
            end={end}
            onClick={() => setOpen(false)}
            className={({ isActive }) =>
              `flex items-center gap-2.5 rounded-lg px-3 py-2 text-sm transition-colors ${
                isActive ? 'bg-accent-soft font-medium text-accent' : 'text-ink-2 hover:bg-surface-2 hover:text-ink'
              }`
            }
          >
            <Icon className="size-4" aria-hidden />
            {t(label)}
          </NavLink>
        )),
      ])}
    </nav>
  )

  const account = (
    <div className="border-t border-line pt-3">
      <p className="truncate px-3 text-xs text-muted">{t('nav.signedInAs')}</p>
      <p className="truncate px-3 text-sm font-medium">{me?.username}</p>
      <button
        type="button"
        onClick={logout}
        className="mt-2 flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-sm text-ink-2 hover:bg-surface-2 hover:text-ink"
      >
        <LogOut className="size-4" aria-hidden /> {t('nav.logout')}
      </button>
      <p className="mt-2 px-3 text-xs text-muted">{t('nav.version', { version: import.meta.env.APP_VERSION })}</p>
    </div>
  )

  return (
    <div className="min-h-dvh lg:grid lg:grid-cols-[15rem_1fr] print:block">
      {/* Desktop sidebar */}
      <div className="hidden border-r border-line bg-surface lg:block print:hidden">
        <aside className="sticky top-0 flex h-dvh flex-col justify-between gap-3 overflow-y-auto p-3">
          <div>
            <Brand />
            {nav}
          </div>
          {account}
        </aside>
      </div>

      {/* Mobile top bar */}
      <header className="sticky top-0 z-20 flex items-center justify-between border-b border-line bg-surface px-4 py-2 lg:hidden print:hidden">
        <Brand />
        <button type="button" className="rounded-md p-2 text-ink-2" onClick={() => setOpen((v) => !v)}
          aria-label={open ? t('nav.closeMenu') : t('nav.openMenu')} aria-expanded={open}>
          {open ? <X className="size-5" /> : <Menu className="size-5" />}
        </button>
      </header>
      {open && (
        <div className="fixed inset-x-0 top-[3.25rem] bottom-0 z-10 flex flex-col justify-between overflow-y-auto bg-surface p-3 lg:hidden print:hidden">
          {nav}
          {account}
        </div>
      )}

      <main className="mx-auto w-full max-w-6xl px-4 py-6 sm:px-6 lg:py-8 print:max-w-none print:p-0">
        <Suspense fallback={<Spinner />}>
          <Outlet />
        </Suspense>
      </main>
    </div>
  )
}

function Brand() {
  const { t } = useI18n()
  return (
    <div className="flex items-center gap-2 px-3 py-2 lg:mb-4">
      <div className="flex size-7 items-center justify-center rounded-lg bg-accent text-white">
        <PiggyBank className="size-4" aria-hidden />
      </div>
      <span className="text-sm font-semibold">{t('app.name')}</span>
    </div>
  )
}
