import type { ReactNode } from 'react'
import { Navigate, Route, Routes } from 'react-router'
import { LoginPage } from './auth/LoginPage'
import { RequireAuth } from './auth/RequireAuth'
import { Layout } from './components/Layout'
import { OverviewPage } from './pages/OverviewPage'
import { EntriesPage } from './pages/EntriesPage'
import { CashflowPage } from './pages/CashflowPage'
import { NetWorthPage } from './pages/NetWorthPage'
import { PositionsPage } from './pages/PositionsPage'
import { PositionDetailPage } from './pages/PositionDetailPage'
import { BulkUpdatePage } from './pages/BulkUpdatePage'
import { SettingsPage } from './pages/settings/SettingsPage'
import { AdminUsersPage } from './pages/AdminUsersPage'
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
          <Route path="flussi" element={<CashflowPage />} />
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
