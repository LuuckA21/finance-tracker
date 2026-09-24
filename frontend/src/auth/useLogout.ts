import { useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { clearCsrf, post } from '../api/client'

export function useLogout() {
  const qc = useQueryClient()
  const navigate = useNavigate()
  return async () => {
    try {
      await post('/api/auth/logout')
    } catch {
      // Session may already be gone; clear local state regardless
    }
    clearCsrf()
    qc.clear()
    navigate('/login', { replace: true })
  }
}
