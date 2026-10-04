import { useEffect, useRef } from 'react'
import { useMutation, useQuery, useQueryClient, keepPreviousData } from '@tanstack/react-query'
import { del, get, patch, post, put, upload } from './client'
import type {
  AdminUser,
  AnnualReport,
  AssetClass,
  CashEntry,
  CashflowYear,
  CashflowYears,
  Category,
  CategoryRule,
  CategoryTrend,
  CategorySuggestion,
  BudgetPeriod,
  Passkey,
  BudgetStatus,
  Goal,
  GoalKind,
  CategoryKind,
  EntryKind,
  CentralRates,
  EcbStatus,
  Forecast,
  ForecastInput,
  ForecastScenario,
  FxRate,
  ImportPreview,
  Granularity,
  LoginEvent,
  Me,
  NetWorthDetail,
  NetWorthSeries,
  NotificationSettings,
  Page,
  Position,
  RecurringEntry,
  Snapshot,
  Tags,
  UserWithPassword,
} from './types'
import type { Language } from '../i18n'
import type { Theme } from '../preferences/theme'

// Every mutation that changes financial data invalidates the dashboards too.
const FINANCE_KEYS = [['entries'], ['dashboard'], ['positions'], ['fx'], ['budgets'], ['goals'], ['tags']] as const

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

/** What the login page can offer (passkeys need a configured public address). */
export const useAuthConfig = () =>
  useQuery({ queryKey: ['auth-config'], queryFn: () => get<{ passkeys: boolean }>('/api/auth/config'), staleTime: Infinity })

// ---------------------------------------------------------------- passkeys

export const usePasskeys = () =>
  useQuery({ queryKey: ['passkeys'], queryFn: () => get<Passkey[]>('/api/account/passkeys') })

export function useRenamePasskey() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, name }: { id: number; name: string }) => put<Passkey>(`/api/account/passkeys/${id}`, { name }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['passkeys'] }),
  })
}

export function useDeletePasskey() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => del(`/api/account/passkeys/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['passkeys'] }),
  })
}

// ---------------------------------------------------------------- notifications

export const useNotificationSettings = () =>
  useQuery({ queryKey: ['notifications'], queryFn: () => get<NotificationSettings>('/api/account/notifications') })

/** Every notification call answers with the new settings: store them as they come. */
function useNotificationMutation<T>(call: (arg: T) => Promise<NotificationSettings>) {
  const qc = useQueryClient()
  return useMutation({ mutationFn: call, onSuccess: (data) => qc.setQueryData(['notifications'], data) })
}

export const useUpdateNotifications = () => useNotificationMutation(
  (body: Partial<Pick<NotificationSettings, 'budgetAlerts' | 'goalAlerts' | 'monthlySummary'>>) =>
    put<NotificationSettings>('/api/account/notifications', body))

export const useRequestEmailCode = () => useNotificationMutation(
  (email: string) => post<NotificationSettings>('/api/account/notifications/email', { email }))

export const useConfirmEmail = () => useNotificationMutation(
  (code: string) => post<NotificationSettings>('/api/account/notifications/email/confirm', { code }))

export const useRemoveEmail = () => useNotificationMutation(
  () => del<NotificationSettings>('/api/account/notifications/email'))

export const useSendTestEmail = () => useMutation({ mutationFn: () => post<void>('/api/account/notifications/test', {}) })

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
    mutationFn: (c: { id?: number; name: string; kind: CategoryKind; color: string; parentId: number | null }) =>
      c.id
        ? put<Category>(`/api/categories/${c.id}`, { name: c.name, color: c.color, parentId: c.parentId })
        : post<Category>('/api/categories', c),
    // Names and levels show everywhere: entries, budgets, reports, forecasts
    onSuccess: () => qc.invalidateQueries(),
  })
}

export function useDeleteCategory() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => del(`/api/categories/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['categories'] }),
  })
}

// ---------------------------------------------------------------- category rules

export const useCategoryRules = () =>
  useQuery({ queryKey: ['category-rules'], queryFn: () => get<CategoryRule[]>('/api/category-rules') })

export function useSaveCategoryRule() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (r: { id?: number; pattern: string; categoryId: number }) =>
      r.id ? put<CategoryRule>(`/api/category-rules/${r.id}`, r) : post<CategoryRule>('/api/category-rules', r),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['category-rules'] }),
  })
}

export function useDeleteCategoryRule() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => del(`/api/category-rules/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['category-rules'] }),
  })
}

/** The category the rules or the past entries suggest for a description (null when none does). */
export const useCategorySuggestion = (description: string, kind: CategoryKind, enabled: boolean) =>
  useQuery({
    queryKey: ['category-rules', 'suggest', description, kind],
    queryFn: () => get<CategorySuggestion | undefined>(
      `/api/category-rules/suggest?${new URLSearchParams({ description, kind })}`).then((s) => s ?? null),
    enabled,
    staleTime: 60_000,
  })

// ---------------------------------------------------------------- cash entries

export interface EntryFilter {
  from?: string
  to?: string
  kind?: EntryKind | ''
  categoryId?: number | ''
  q?: string
  tagId?: number | ''
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

export type EntryInput = Omit<CashEntry, 'id' | 'recurringEntryId' | 'splitGroup'> & { id?: number }

/** Query string of the entry filters (without paging), for the list and the CSV export. */
export function entryFilterParams(filter: Omit<EntryFilter, 'page' | 'size'>): URLSearchParams {
  const params = new URLSearchParams()
  Object.entries(filter).forEach(([k, v]) => {
    if (v !== undefined && v !== '' && v !== null) params.set(k, String(v))
  })
  return params
}

/** First import step: the server reads the file and reports every row; nothing is saved. */
export function useImportPreview() {
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData()
      form.append('file', file)
      return upload<ImportPreview>('/api/cash-entries/import/preview', form)
    },
  })
}

/** The closing balances of bank statements, as the value of the positions with their IBAN on that day. */
export function useUpdateBalances() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: async (balances: { positionId: number; date: string; balance: number; note: string }[]) => {
      for (const b of balances) {
        await post<Snapshot>(`/api/positions/${b.positionId}/snapshots`, { date: b.date, quantity: b.balance, unitPrice: 1, note: b.note })
      }
    },
    onSettled: () => invalidate(),
  })
}

/** Second step: the confirmed rows, saved all together or not at all. */
export function useImportEntries() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (entries: Omit<EntryInput, 'id'>[]) => post<{ imported: number }>('/api/cash-entries/import', { entries }),
    onSuccess: () => invalidate(),
  })
}

export function useSaveEntry() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ id, ...body }: EntryInput) =>
      id ? put<CashEntry>(`/api/cash-entries/${id}`, body) : post<CashEntry>('/api/cash-entries', body),
    onSuccess: () => invalidate(),
  })
}

/** The parts of a split entry: one payment shared among categories. */
export const useSplit = (group: string | null) =>
  useQuery({ queryKey: ['split', group], queryFn: () => get<CashEntry[]>(`/api/cash-entries/split/${group}`), enabled: group !== null })

export interface SplitInput {
  /** The split entry to change; a new one without it */
  group?: string | null
  /** On creation: an ordinary entry the parts take the place of */
  replaces?: number | null
  date: string
  kind: 'INCOME' | 'EXPENSE'
  currency: string
  description: string | null
  tags: string[]
  parts: { categoryId: number; amount: number }[]
}

export function useSaveSplit() {
  const invalidate = useInvalidate()
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ group, ...body }: SplitInput) =>
      group ? put<CashEntry[]>(`/api/cash-entries/split/${group}`, body) : post<CashEntry[]>('/api/cash-entries/split', body),
    onSuccess: () => {
      invalidate()
      qc.removeQueries({ queryKey: ['split'] })
    },
  })
}

export function useDeleteSplit() {
  const invalidate = useInvalidate()
  return useMutation({ mutationFn: (group: string) => del(`/api/cash-entries/split/${group}`), onSuccess: () => invalidate() })
}

/** Several entries changed (category, tags) or deleted at once, all or none. */
export interface BulkInput {
  ids: number[]
  action: 'UPDATE' | 'DELETE'
  categoryId?: number | null
  addTags?: string[]
  removeTags?: string[]
}

export function useBulkEntries() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (body: BulkInput) => post<{ updated: number; skipped: number }>('/api/cash-entries/bulk', body),
    onSuccess: () => invalidate(),
  })
}

/** The ids of every entry matching the filters (at most 5000), to select them all. */
export function fetchEntryIds(filter: Omit<EntryFilter, 'page' | 'size'>) {
  return get<{ ids: number[]; total: number }>(`/api/cash-entries/ids?${entryFilterParams(filter)}`)
}

export function useDeleteEntry() {
  const invalidate = useInvalidate()
  return useMutation({ mutationFn: (id: number) => del(`/api/cash-entries/${id}`), onSuccess: () => invalidate() })
}

// ---------------------------------------------------------------- tags

export const useTags = () => useQuery({ queryKey: ['tags'], queryFn: () => get<Tags>('/api/tags') })

export function useRenameTag() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ id, name }: { id: number; name: string }) => put<unknown>(`/api/tags/${id}`, { name }),
    // Entries and recurring rules show tag names: refresh them too
    onSuccess: () => invalidate([['tags'], ['entries'], ['recurring']]),
  })
}

export function useDeleteTag() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (id: number) => del(`/api/tags/${id}`),
    onSuccess: () => invalidate([['tags'], ['entries'], ['recurring']]),
  })
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
  /** Empty for none */
  iban: string
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

/** ECB rates in effect on a day (yyyy-MM-dd). */
export const useCentralRates = (date: string) =>
  useQuery({
    queryKey: ['fx', 'central', date],
    queryFn: () => get<CentralRates>(`/api/fx-rates/central?date=${encodeURIComponent(date)}`),
    placeholderData: keepPreviousData,
  })

/** Admin only. Polls while a history download runs, then refreshes rates and dashboards. */
export function useEcbStatus(enabled: boolean) {
  const invalidate = useInvalidate()
  const query = useQuery({
    queryKey: ['fx', 'ecb-status'],
    queryFn: () => get<EcbStatus>('/api/admin/fx'),
    enabled,
    refetchInterval: (q) => (q.state.data?.historyRunning ? 2000 : false),
  })
  const running = query.data?.historyRunning ?? false
  const wasRunning = useRef(running)
  useEffect(() => {
    if (wasRunning.current && !running) void invalidate()
    wasRunning.current = running
  }, [running, invalidate])
  return query
}

export function useDownloadEcbHistory() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => post<EcbStatus>('/api/admin/fx/history', {}),
    onSettled: () => qc.invalidateQueries({ queryKey: ['fx', 'ecb-status'] }),
  })
}

export function useRefreshEcb() {
  const invalidate = useInvalidate()
  // onSettled: a failed download changes the status too
  return useMutation({ mutationFn: () => post<unknown>('/api/admin/fx/refresh', {}), onSettled: () => invalidate() })
}

// ---------------------------------------------------------------- budgets

/** Budgets compared with the spending of a month (yyyy-MM), default the current one. */
export const useBudgetStatus = (month?: string) =>
  useQuery({
    queryKey: ['budgets', 'status', month ?? 'current'],
    queryFn: () => get<BudgetStatus>(`/api/budgets/status${month ? `?month=${month}` : ''}`),
    placeholderData: keepPreviousData,
  })

export function useSaveBudget() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ categoryId, ...body }: { categoryId: number; amount: number; currency: string; period: BudgetPeriod }) =>
      put<unknown>(`/api/budgets/${categoryId}`, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['budgets'] }),
  })
}

export function useDeleteBudget() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (categoryId: number) => del(`/api/budgets/${categoryId}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['budgets'] }),
  })
}

// ---------------------------------------------------------------- goals

export const useGoals = () => useQuery({ queryKey: ['goals'], queryFn: () => get<Goal[]>('/api/goals') })

export interface GoalInput {
  id?: number
  name: string
  kind: GoalKind
  targetAmount: number
  currency: string
  targetDate: string | null
  positionIds: number[]
}

export function useSaveGoal() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...body }: GoalInput) => (id ? put<Goal>(`/api/goals/${id}`, body) : post<Goal>('/api/goals', body)),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['goals'] }),
  })
}

export function useDeleteGoal() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => del(`/api/goals/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['goals'] }),
  })
}

// ---------------------------------------------------------------- forecasts

export const useForecasts = () => useQuery({ queryKey: ['forecasts'], queryFn: () => get<ForecastScenario[]>('/api/forecasts') })

export function useSaveForecast() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...body }: ForecastInput & { id?: number }) =>
      id ? put<ForecastScenario>(`/api/forecasts/${id}`, body) : post<ForecastScenario>('/api/forecasts', body),
    // In the list at once, so the page can select it before the refetch
    onSuccess: (saved) => {
      qc.setQueryData<ForecastScenario[]>(['forecasts'], (old) => old && [...old.filter((s) => s.id !== saved.id), saved])
      return qc.invalidateQueries({ queryKey: ['forecasts'] })
    },
  })
}

export function useDeleteForecast() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => del(`/api/forecasts/${id}`),
    onSuccess: (_, id) => {
      qc.setQueryData<ForecastScenario[]>(['forecasts'], (old) => old?.filter((s) => s.id !== id))
      return qc.invalidateQueries({ queryKey: ['forecasts'] })
    },
  })
}

/** The forecast of a scenario being edited (saved or not); under 'dashboard', so new entries refresh it. */
export const useForecastPreview = (input: ForecastInput | null) =>
  useQuery({
    queryKey: ['dashboard', 'forecast', input],
    queryFn: () => post<Forecast>('/api/forecasts/preview', input),
    enabled: input !== null,
    placeholderData: keepPreviousData,
  })

// ---------------------------------------------------------------- dashboards

export const useCashflowYear = (year: number) =>
  useQuery({
    queryKey: ['dashboard', 'cashflow', year],
    queryFn: () => get<CashflowYear>(`/api/dashboard/cashflow?year=${year}`),
    placeholderData: keepPreviousData,
  })

export const useAnnualReport = (year: number) =>
  useQuery({
    queryKey: ['dashboard', 'annual', year],
    queryFn: () => get<AnnualReport>(`/api/reports/annual?year=${year}`),
    placeholderData: keepPreviousData,
  })

export const useCategoryTrend = (categoryId: number, year: number) =>
  useQuery({
    queryKey: ['dashboard', 'category-trend', categoryId, year],
    queryFn: () => get<CategoryTrend>(`/api/dashboard/category-trend?categoryId=${categoryId}&year=${year}`),
    placeholderData: keepPreviousData,
    enabled: Number.isFinite(categoryId),
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
