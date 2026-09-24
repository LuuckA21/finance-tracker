// Types mirroring the backend JSON responses.

import type { Language } from '../i18n'
import type { Theme } from '../preferences/theme'

export type Role = 'ADMIN' | 'USER'
export type EntryKind = 'INCOME' | 'EXPENSE'
export type AssetClass =
  | 'CASH'
  | 'CRYPTO'
  | 'ETF'
  | 'STOCK'
  | 'BOND'
  | 'FUND'
  | 'PENSION'
  | 'COMMODITY'
  | 'REAL_ESTATE'
  | 'OTHER'

export interface Me {
  id: number
  username: string
  role: Role
  baseCurrency: string
  language: Language
  theme: Theme
  mfaEnabled: boolean
  recoveryCodesRemaining: number
  passwordChangeRequired: boolean
}

export interface Category {
  id: number
  name: string
  kind: EntryKind
  color: string
}

export interface CashEntry {
  id: number
  date: string
  kind: EntryKind
  categoryId: number
  amount: number
  currency: string
  description: string | null
}

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface Snapshot {
  id: number
  date: string
  quantity: number
  unitPrice: number
  value: number
  note: string | null
}

export interface Position {
  id: number
  name: string
  symbol: string | null
  assetClass: AssetClass
  currency: string
  notes: string | null
  archived: boolean
  latest: Snapshot | null
}

export interface FxRate {
  id: number
  baseCurrency: string
  currency: string
  date: string
  rate: number
}

export interface CashflowTotals {
  income: number
  expense: number
  net: number
  savingsRate: number | null
}

export interface CashflowYear {
  baseCurrency: string
  year: number
  months: { month: number; totals: CashflowTotals }[]
  totals: CashflowTotals
  categories: {
    categoryId: number
    name: string
    color: string
    kind: EntryKind
    amount: number
    share: number | null
  }[]
  availableYears: number[]
  unconvertedCurrencies: string[]
}

export interface CashflowYears {
  baseCurrency: string
  years: { year: number; totals: CashflowTotals }[]
  unconvertedCurrencies: string[]
}

export type Granularity = 'MONTH' | 'YEAR'

export interface NetWorthPoint {
  date: string
  period: string
  total: number
  byClass: Partial<Record<AssetClass, number>>
}

export interface NetWorthSeries {
  baseCurrency: string
  granularity: Granularity
  points: NetWorthPoint[]
  firstSnapshotDate: string | null
  unconvertedCurrencies: string[]
}

export interface PositionValue {
  positionId: number
  name: string
  symbol: string | null
  assetClass: AssetClass
  currency: string
  archived: boolean
  asOf: string
  quantity: number
  unitPrice: number
  valueLocal: number
  valueBase: number | null
  share: number | null
}

export interface NetWorthDetail {
  baseCurrency: string
  date: string
  total: number
  byClass: Partial<Record<AssetClass, number>>
  positions: PositionValue[]
  unconvertedCurrencies: string[]
}

export interface LoginEvent {
  at: string
  ipAddress: string | null
  userAgent: string | null
  success: boolean
  reason: string
}

export interface AdminUser {
  id: number
  username: string
  role: Role
  enabled: boolean
  mfaEnabled: boolean
  locked: boolean
  passwordChangeRequired: boolean
  lastLoginAt: string | null
  createdAt: string
}

export interface UserWithPassword {
  user: AdminUser
  temporaryPassword: string | null
}
