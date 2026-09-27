import { useMutation, useQuery, useQueryClient, keepPreviousData } from '@tanstack/react-query'
import { del, get, patch, post, put } from './client'
import type {
  AdminUser,
  AssetClass,
  CashEntry,
  CashflowYear,
  CashflowYears,
  Category,
  EntryKind,
  CentralRates,
  EcbStatus,
  FxRate,
  Granularity,
  LoginEvent,
  Me,
  NetWorthDetail,
  NetWorthSeries,
  Page,
  Position,
  RecurringEntry,
  Snapshot,
  UserWithPassword,
} from './types'
import type { Language } from '../i18n'
import type { Theme } from '../preferences/theme'

// Every mutation that changes financial data invalidates the dashboards too.
const FINANCE_KEYS = [['entries'], ['dashboard'], ['positions'], ['fx']] as const

function useInvalidate() {
  const qc = useQueryClient()
  return (keys: readonly (readonly string[])[] = FINANCE_KEYS) =>
    Promise.all(keys.map((key) => qc.invalidateQueries({ queryKey: key })))
}

// ---------------------------------------------------------------- account

export const useMe = () =>
  useQuery({ queryKey: ['me'], queryFn: () => get<Me>('/api/auth/me'), retry: false, staleTime: 60_000 })

export function useChangePassword() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { currentPassword: string; newPassword: string }) => put<Me>('/api/account/password', body),
    onSuccess: (me) => qc.setQueryData(['me'], me),
  })
}

export function useUpdateSettings() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { baseCurrency?: string; language?: Language; theme?: Theme }) =>
      put<Me>('/api/account/settings', body),
    onSuccess: async (me, body) => {
      qc.setQueryData(['me'], me)
      // Only the base currency changes the numbers shown everywhere
      if (body.baseCurrency) await qc.invalidateQueries()
    },
  })
}

export const useLoginHistory = () =>
  useQuery({ queryKey: ['logins'], queryFn: () => get<LoginEvent[]>('/api/account/logins') })

export const useMfaSetup = () =>
  useMutation({ mutationFn: () => post<{ secret: string; otpauthUri: string }>('/api/account/mfa/setup') })

export function useMfaEnable() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { password: string; code: string }) =>
      post<{ recoveryCodes: string[] }>('/api/account/mfa/enable', body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['me'] }),
  })
}

export function useMfaDisable() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { password: string; code: string }) => post<void>('/api/account/mfa/disable', body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['me'] }),
  })
}

export function useRegenerateRecoveryCodes() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (code: string) => post<{ recoveryCodes: string[] }>('/api/account/mfa/recovery-codes', { code }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['me'] }),
  })
}

// ---------------------------------------------------------------- categories

export const useCategories = () =>
  useQuery({ queryKey: ['categories'], queryFn: () => get<Category[]>('/api/categories'), staleTime: 60_000 })

export function useSaveCategory() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (c: { id?: number; name: string; kind: EntryKind; color: string }) =>
      c.id
        ? put<Category>(`/api/categories/${c.id}`, { name: c.name, color: c.color })
        : post<Category>('/api/categories', c),
    onSuccess: () => Promise.all([qc.invalidateQueries({ queryKey: ['categories'] }), qc.invalidateQueries({ queryKey: ['dashboard'] })]),
  })
}

export function useDeleteCategory() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => del(`/api/categories/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['categories'] }),
  })
}

// ---------------------------------------------------------------- cash entries

export interface EntryFilter {
  from?: string
  to?: string
  kind?: EntryKind | ''
  categoryId?: number | ''
  q?: string
  page: number
  size: number
}

export function useEntries(filter: EntryFilter) {
  const params = new URLSearchParams()
  Object.entries(filter).forEach(([k, v]) => {
    if (v !== undefined && v !== '' && v !== null) params.set(k, String(v))
  })
  return useQuery({
    queryKey: ['entries', filter],
    queryFn: () => get<Page<CashEntry>>(`/api/cash-entries?${params}`),
    placeholderData: keepPreviousData,
  })
}

export type EntryInput = Omit<CashEntry, 'id' | 'recurringEntryId'> & { id?: number }

export function useSaveEntry() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ id, ...body }: EntryInput) =>
      id ? put<CashEntry>(`/api/cash-entries/${id}`, body) : post<CashEntry>('/api/cash-entries', body),
    onSuccess: () => invalidate(),
  })
}

export function useDeleteEntry() {
  const invalidate = useInvalidate()
  return useMutation({ mutationFn: (id: number) => del(`/api/cash-entries/${id}`), onSuccess: () => invalidate() })
}

// ---------------------------------------------------------------- recurring entries

export const useRecurringEntries = () =>
  useQuery({ queryKey: ['recurring'], queryFn: () => get<RecurringEntry[]>('/api/recurring-entries') })

export type RecurringInput = Omit<RecurringEntry, 'id' | 'lastGenerated' | 'nextDate'> & { id?: number }

// Saving a rule may create entries right away, so the entries and dashboards are refreshed too
export function useSaveRecurring() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ id, ...body }: RecurringInput) =>
      id ? put<RecurringEntry>(`/api/recurring-entries/${id}`, body) : post<RecurringEntry>('/api/recurring-entries', body),
    onSuccess: () => invalidate([...FINANCE_KEYS, ['recurring']]),
  })
}

export function useDeleteRecurring() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (id: number) => del(`/api/recurring-entries/${id}`),
    onSuccess: () => invalidate([['recurring'], ['entries']]),
  })
}

// ---------------------------------------------------------------- positions

export const usePositions = () =>
  useQuery({ queryKey: ['positions'], queryFn: () => get<Position[]>('/api/positions') })

export const usePosition = (id: number) =>
  useQuery({ queryKey: ['positions', id], queryFn: () => get<Position>(`/api/positions/${id}`) })

export const useSnapshots = (id: number) =>
  useQuery({ queryKey: ['positions', id, 'snapshots'], queryFn: () => get<Snapshot[]>(`/api/positions/${id}/snapshots`) })

export interface PositionInput {
  id?: number
  name: string
  symbol: string
  assetClass: AssetClass
  currency: string
  notes: string
  archived: boolean
}

export function useSavePosition() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ id, ...body }: PositionInput) =>
      id ? put<Position>(`/api/positions/${id}`, body) : post<Position>('/api/positions', body),
    onSuccess: () => invalidate(),
  })
}

export function useDeletePosition() {
  const invalidate = useInvalidate()
  return useMutation({ mutationFn: (id: number) => del(`/api/positions/${id}`), onSuccess: () => invalidate() })
}

export interface SnapshotInput {
  id?: number
  date: string
  quantity: number
  unitPrice: number
  note: string
}

export function useSaveSnapshot(positionId: number) {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ id, ...body }: SnapshotInput) =>
      id
        ? put<Snapshot>(`/api/positions/${positionId}/snapshots/${id}`, body)
        : post<Snapshot>(`/api/positions/${positionId}/snapshots`, body),
    onSuccess: () => invalidate(),
  })
}

export function useDeleteSnapshot(positionId: number) {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (id: number) => del(`/api/positions/${positionId}/snapshots/${id}`),
    onSuccess: () => invalidate(),
  })
}

export function useBulkSnapshot() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (body: { date: string; items: { positionId: number; quantity: number; unitPrice: number }[] }) =>
      post<{ saved: number }>('/api/positions/snapshots/bulk', body),
    onSuccess: () => invalidate(),
  })
}

// ---------------------------------------------------------------- fx

export const useFxRates = () => useQuery({ queryKey: ['fx'], queryFn: () => get<FxRate[]>('/api/fx-rates') })

export function useSaveFxRate() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (body: { currency: string; date: string; rate: number }) => post<FxRate>('/api/fx-rates', body),
    onSuccess: () => invalidate(),
  })
}

export function useDeleteFxRate() {
  const invalidate = useInvalidate()
  return useMutation({ mutationFn: (id: number) => del(`/api/fx-rates/${id}`), onSuccess: () => invalidate() })
}

export const useCentralRates = () =>
  useQuery({ queryKey: ['fx', 'central'], queryFn: () => get<CentralRates>('/api/fx-rates/central') })

export const useEcbStatus = (enabled: boolean) =>
  useQuery({ queryKey: ['fx', 'ecb-status'], queryFn: () => get<EcbStatus>('/api/admin/fx'), enabled })

export function useRefreshEcb() {
  const invalidate = useInvalidate()
  // onSettled: a failed download changes the status too
  return useMutation({ mutationFn: () => post<unknown>('/api/admin/fx/refresh', {}), onSettled: () => invalidate() })
}

// ---------------------------------------------------------------- dashboards

export const useCashflowYear = (year: number) =>
  useQuery({
    queryKey: ['dashboard', 'cashflow', year],
    queryFn: () => get<CashflowYear>(`/api/dashboard/cashflow?year=${year}`),
    placeholderData: keepPreviousData,
  })

export const useCashflowYears = () =>
  useQuery({ queryKey: ['dashboard', 'cashflow-years'], queryFn: () => get<CashflowYears>('/api/dashboard/cashflow/years') })

export function useNetWorthSeries(granularity: Granularity, from?: string, to?: string) {
  const params = new URLSearchParams({ granularity })
  if (from) params.set('from', from)
  if (to) params.set('to', to)
  return useQuery({
    queryKey: ['dashboard', 'net-worth', granularity, from, to],
    queryFn: () => get<NetWorthSeries>(`/api/dashboard/net-worth?${params}`),
    placeholderData: keepPreviousData,
  })
}

export const useNetWorthDetail = (date?: string) =>
  useQuery({
    queryKey: ['dashboard', 'net-worth-detail', date ?? 'today'],
    queryFn: () => get<NetWorthDetail>(`/api/dashboard/net-worth/detail${date ? `?date=${date}` : ''}`),
    placeholderData: keepPreviousData,
  })

// ---------------------------------------------------------------- admin

export const useAdminUsers = () =>
  useQuery({ queryKey: ['admin', 'users'], queryFn: () => get<AdminUser[]>('/api/admin/users') })

export function useAdminAction() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (action: AdminActionInput): Promise<UserWithPassword | AdminUser | void> => {
      switch (action.type) {
        case 'create':
          return post<UserWithPassword>('/api/admin/users', action.body)
        case 'update':
          return patch<AdminUser>(`/api/admin/users/${action.id}`, action.body)
        case 'reset-password':
          return post<UserWithPassword>(`/api/admin/users/${action.id}/reset-password`)
        case 'unlock':
          return post<AdminUser>(`/api/admin/users/${action.id}/unlock`)
        case 'reset-mfa':
          return post<AdminUser>(`/api/admin/users/${action.id}/reset-mfa`)
        case 'delete':
          return del(`/api/admin/users/${action.id}`)
      }
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin', 'users'] }),
  })
}

export type AdminActionInput =
  | { type: 'create'; body: { username: string; role: 'ADMIN' | 'USER'; password?: string; language?: Language } }
  | { type: 'update'; id: number; body: { role?: 'ADMIN' | 'USER'; enabled?: boolean } }
  | { type: 'reset-password' | 'unlock' | 'reset-mfa' | 'delete'; id: number }
