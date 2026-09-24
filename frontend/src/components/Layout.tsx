import { useState } from 'react'
import { NavLink, Outlet } from 'react-router'
import {
  ArrowLeftRight,
  BarChart3,
  LayoutDashboard,
  LogOut,
  Menu,
  PiggyBank,
  RefreshCw,
  Settings,
  Users,
  Wallet,
  X,
} from 'lucide-react'
import { useMe } from '../api/hooks'
import { useLogout } from '../auth/useLogout'

const NAV = [
  { to: '/', label: 'Panoramica', icon: LayoutDashboard, end: true },
  { to: '/movimenti', label: 'Movimenti', icon: ArrowLeftRight },
  { to: '/flussi', label: 'Entrate e uscite', icon: BarChart3 },
  { to: '/patrimonio', label: 'Patrimonio', icon: PiggyBank },
  { to: '/posizioni', label: 'Posizioni', icon: Wallet },
  { to: '/aggiorna', label: 'Aggiorna valori', icon: RefreshCw },
  { to: '/impostazioni', label: 'Impostazioni', icon: Settings },
]

export function Layout() {
  const me = useMe().data
  const logout = useLogout()
  const [open, setOpen] = useState(false)

  const items = me?.role === 'ADMIN'
    ? [...NAV, { to: '/admin/utenti', label: 'Utenti', icon: Users }]
    : NAV

  const nav = (
    <nav className="flex flex-col gap-0.5" aria-label="Navigazione principale">
      {items.map(({ to, label, icon: Icon, ...rest }) => (
        <NavLink
          key={to}
          to={to}
          end={'end' in rest}
          onClick={() => setOpen(false)}
          className={({ isActive }) =>
            `flex items-center gap-2.5 rounded-lg px-3 py-2 text-sm transition-colors ${
              isActive ? 'bg-accent-soft font-medium text-accent' : 'text-ink-2 hover:bg-surface-2 hover:text-ink'
            }`
          }
        >
          <Icon className="size-4" aria-hidden />
          {label}
        </NavLink>
      ))}
    </nav>
  )

  const account = (
    <div className="border-t border-line pt-3">
      <p className="truncate px-3 text-xs text-muted">Connesso come</p>
      <p className="truncate px-3 text-sm font-medium">{me?.username}</p>
      <button
        type="button"
        onClick={logout}
        className="mt-2 flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-sm text-ink-2 hover:bg-surface-2 hover:text-ink"
      >
        <LogOut className="size-4" aria-hidden /> Esci
      </button>
    </div>
  )

  return (
    <div className="min-h-dvh lg:grid lg:grid-cols-[15rem_1fr]">
      {/* Desktop sidebar */}
      <div className="hidden border-r border-line bg-surface lg:block">
        <aside className="sticky top-0 flex h-dvh flex-col justify-between p-3">
          <div>
            <Brand />
            {nav}
          </div>
          {account}
        </aside>
      </div>

      {/* Mobile top bar */}
      <header className="sticky top-0 z-20 flex items-center justify-between border-b border-line bg-surface px-4 py-2 lg:hidden">
        <Brand />
        <button type="button" className="rounded-md p-2 text-ink-2" onClick={() => setOpen((v) => !v)}
          aria-label={open ? 'Chiudi menu' : 'Apri menu'} aria-expanded={open}>
          {open ? <X className="size-5" /> : <Menu className="size-5" />}
        </button>
      </header>
      {open && (
        <div className="fixed inset-x-0 top-[3.25rem] bottom-0 z-10 flex flex-col justify-between overflow-y-auto bg-surface p-3 lg:hidden">
          {nav}
          {account}
        </div>
      )}

      <main className="mx-auto w-full max-w-6xl px-4 py-6 sm:px-6 lg:py-8">
        <Outlet />
      </main>
    </div>
  )
}

function Brand() {
  return (
    <div className="flex items-center gap-2 px-3 py-2 lg:mb-4">
      <div className="flex size-7 items-center justify-center rounded-lg bg-accent text-white">
        <PiggyBank className="size-4" aria-hidden />
      </div>
      <span className="text-sm font-semibold">Finanze</span>
    </div>
  )
}
