import { lazy, type ReactNode } from 'react'
import { Navigate, Route, Routes } from 'react-router'
import { LoginPage } from './auth/LoginPage'
import { RequireAuth } from './auth/RequireAuth'
import { Layout } from './components/Layout'

// Pages are separate chunks, downloaded on first visit (Layout shows a spinner meanwhile)
const OverviewPage = lazy(() => import('./pages/OverviewPage').then((m) => ({ default: m.OverviewPage })))
const EntriesPage = lazy(() => import('./pages/EntriesPage').then((m) => ({ default: m.EntriesPage })))
const RecurringPage = lazy(() => import('./pages/RecurringPage').then((m) => ({ default: m.RecurringPage })))
const GoalsPage = lazy(() => import('./pages/GoalsPage').then((m) => ({ default: m.GoalsPage })))
const BudgetPage = lazy(() => import('./pages/BudgetPage').then((m) => ({ default: m.BudgetPage })))
const AnnualReportPage = lazy(() => import('./pages/AnnualReportPage').then((m) => ({ default: m.AnnualReportPage })))
const CashflowPage = lazy(() => import('./pages/CashflowPage').then((m) => ({ default: m.CashflowPage })))
const NetWorthPage = lazy(() => import('./pages/NetWorthPage').then((m) => ({ default: m.NetWorthPage })))
const PositionsPage = lazy(() => import('./pages/PositionsPage').then((m) => ({ default: m.PositionsPage })))
const PositionDetailPage = lazy(() => import('./pages/PositionDetailPage').then((m) => ({ default: m.PositionDetailPage })))
const BulkUpdatePage = lazy(() => import('./pages/BulkUpdatePage').then((m) => ({ default: m.BulkUpdatePage })))
const SettingsPage = lazy(() => import('./pages/settings/SettingsPage').then((m) => ({ default: m.SettingsPage })))
const AdminUsersPage = lazy(() => import('./pages/AdminUsersPage').then((m) => ({ default: m.AdminUsersPage })))
import { useMe } from './api/hooks'

function AdminOnly({ children }: { children: ReactNode }) {
  const me = useMe().data
  return me?.role === 'ADMIN' ? children : <Navigate to="/" replace />
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireAuth />}>
        <Route element={<Layout />}>
          <Route index element={<OverviewPage />} />
          <Route path="movimenti" element={<EntriesPage />} />
          <Route path="ricorrenti" element={<RecurringPage />} />
          <Route path="flussi" element={<CashflowPage />} />
          <Route path="riepilogo" element={<AnnualReportPage />} />
          <Route path="budget" element={<BudgetPage />} />
          <Route path="obiettivi" element={<GoalsPage />} />
          <Route path="patrimonio" element={<NetWorthPage />} />
          <Route path="posizioni" element={<PositionsPage />} />
          <Route path="posizioni/:id" element={<PositionDetailPage />} />
          <Route path="aggiorna" element={<BulkUpdatePage />} />
          <Route path="impostazioni/:tab?" element={<SettingsPage />} />
          <Route path="admin/utenti" element={<AdminOnly><AdminUsersPage /></AdminOnly>} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
