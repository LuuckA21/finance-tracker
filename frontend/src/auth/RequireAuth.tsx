import { useEffect } from 'react'
import { Navigate, Outlet, useLocation } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { ApiError, onUnauthorized } from '../api/client'
import { useMe } from '../api/hooks'
import { Spinner } from '../components/ui'
import { ForcePasswordChangePage } from './ForcePasswordChangePage'

/** Gate for every authenticated page. */
export function RequireAuth() {
  const me = useMe()
  const qc = useQueryClient()
  const location = useLocation()

  useEffect(
    () =>
      onUnauthorized(() => {
        qc.removeQueries({ predicate: (q) => q.queryKey[0] !== 'me' })
        qc.setQueryData(['me'], null)
      }),
    [qc],
  )

  if (me.isPending) return <Spinner />
  if (me.isError && !(me.error instanceof ApiError && me.error.status === 401)) {
    return <p className="p-8 text-sm text-bad">Impossibile contattare il server. Riprova più tardi.</p>
  }
  if (!me.data) return <Navigate to="/login" replace state={{ from: location.pathname }} />
  if (me.data.passwordChangeRequired) return <ForcePasswordChangePage />
  return <Outlet />
}
