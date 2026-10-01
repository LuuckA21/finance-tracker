// Types mirroring the backend JSON responses.

import type { Language } from '../i18n'
import type { Theme } from '../preferences/theme'

export type Role = 'ADMIN' | 'USER'
/** TRANSFER: money moved between own accounts or investments, neither income nor expense */
export type EntryKind = 'INCOME' | 'EXPENSE' | 'TRANSFER'
/** Categories exist for income and expenses only */
export type CategoryKind = Exclude<EntryKind, 'TRANSFER'>
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

/** A macro category, or a detail category under the macro {@code parentId} */
export interface Category {
  id: number
  name: string
  kind: CategoryKind
  color: string
  parentId: number | null
}

export interface CashEntry {
  id: number
  date: string
  kind: EntryKind
  /** Income/expense only */
  categoryId: number | null
  amount: number
  currency: string
  description: string | null
  /** Set when a recurring rule created the entry */
  recurringEntryId: number | null
  /** Transfers only, both optional */
  fromPositionId: number | null
  toPositionId: number | null
  /** Tag names, sorted; unknown names become new tags when saving */
  tags: string[]
}

export type Frequency = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'QUARTERLY' | 'FOUR_MONTHLY' | 'SEMIANNUAL' | 'YEARLY'

export interface RecurringEntry {
  id: number
  kind: EntryKind
  categoryId: number | null
  fromPositionId: number | null
  toPositionId: number | null
  amount: number
  currency: string
  description: string | null
  frequency: Frequency
  startDate: string
  endDate: string | null
  active: boolean
  lastGenerated: string | null
  /** Null when paused or past the end date */
  nextDate: string | null
  /** Copied to every entry the rule creates */
  tags: string[]
}

export type BudgetState = 'OK' | 'WARNING' | 'OVER'

export interface BudgetStatus {
  baseCurrency: string
  /** yyyy-MM */
  month: string
  currentMonth: boolean
  budgeted: number
  spent: number
  remaining: number
  unbudgeted: number
  categories: {
    categoryId: number
    name: string
    color: string
    /** As entered, in its own currency */
    amount: number
    currency: string
    /** In the base currency; null when the currency cannot be converted */
    budget: number | null
    spent: number
    remaining: number | null
    percent: number | null
    state: BudgetState
    /** Current month only: spending extrapolated to the end of the month */
    projected: number | null
    /** Average monthly spending of the previous 3 months */
    average: number
  }[]
  others: { categoryId: number; name: string; color: string; spent: number; average: number }[]
  unconvertedCurrencies: string[]
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

export type ImportRowError =
  | 'invalid_date' | 'invalid_amount' | 'zero_amount' | 'invalid_currency' | 'description_too_long'
  | 'invalid_kind' | 'missing_category' | 'unknown_category' | 'unknown_subcategory' | 'ambiguous_category'
  | 'category_kind_mismatch'
  | 'unknown_position' | 'transfer_same_position' | 'invalid_tags'

/** One CSV data row: raw text as in the file plus the values that could be read. */
export interface ImportPreviewRow {
  line: number
  raw: Partial<Record<'date' | 'kind' | 'category' | 'subcategory' | 'amount' | 'currency' | 'description' | 'from' | 'to' | 'tags', string>>
  date: string | null
  kind: EntryKind | null
  categoryId: number | null
  amount: number | null
  currency: string | null
  description: string | null
  fromPositionId: number | null
  toPositionId: number | null
  tags: string[]
  duplicate: boolean
  errors: ImportRowError[]
  /** Where categoryId comes from: the file, or the rule rulePattern */
  categorySource: 'FILE' | 'RULE' | null
  rulePattern: string | null
  /** Without a category, the one most often given to the same description in the past: a proposal */
  suggestedCategoryId: number | null
}

/** Entries whose description contains the pattern (ignoring case, accents and punctuation) get the category */
export interface CategoryRule {
  id: number
  pattern: string
  categoryId: number
}

/** A category for a description: from the rule ruleId, or from past entries when it is null */
export interface CategorySuggestion {
  categoryId: number
  ruleId: number | null
  pattern: string | null
}

export interface ImportPreview {
  delimiter: string
  ignoredColumns: string[]
  total: number
  valid: number
  duplicates: number
  invalid: number
  rows: ImportPreviewRow[]
}

/** ECB rate towards the base currency; manualRate is set when the user's own rate is used instead. */
export interface CentralRate {
  currency: string
  rate: number
  date: string
  manualRate: number | null
}

export interface CentralRates {
  source: 'ECB'
  baseCurrency: string
  /** Day the rates apply to (each rate carries the publication it comes from) */
  date: string
  latestDate: string | null
  rates: CentralRate[]
}

export interface EcbStatus {
  autoUpdate: boolean
  latestDate: string | null
  lastAttempt: string | null
  lastSuccess: string | null
  lastError: string | null
  historyRunning: boolean
}

export interface CashflowTotals {
  income: number
  expense: number
  net: number
  savingsRate: number | null
  /** Moved between own accounts/investments: not part of the other figures */
  transferred: number
}

export interface CashflowYear {
  baseCurrency: string
  year: number
  months: { month: number; totals: CashflowTotals }[]
  totals: CashflowTotals
  /** By macro category, largest first */
  categories: {
    categoryId: number
    name: string
    color: string
    kind: CategoryKind
    amount: number
    share: number | null
    /** Largest first, when any detail has entries; the macro's own entries under the macro's id */
    details: { categoryId: number; name: string; color: string; amount: number; share: number | null }[]
  }[]
  /** Transfers by asset class of the destination; null when no destination was given */
  transfers: { destination: AssetClass | null; amount: number; share: number | null }[]
  /** Income or expenses of each tag, largest first; an entry with several tags counts for each */
  tags: { tagId: number; name: string; kind: CategoryKind; amount: number; share: number | null; entryCount: number }[]
  /**
   * Categories × tags, one per kind with tagged entries: a row per category (largest first), a column
   * per tag of `tagIds` (the largest; `tags` keyed by id), then the other tags and no tag
   */
  tagMatrices: {
    kind: CategoryKind
    tagIds: number[]
    rows: { categoryId: number; tags: Record<string, number>; otherTags: number; untagged: number; total: number }[]
    otherTags: number
    untagged: number
  }[]
  availableYears: number[]
  unconvertedCurrencies: string[]
}

export type ForecastSchedule = 'MONTHLY' | 'ONCE'

/** An extra item of a forecast, in the base currency; negative to take something away. */
export interface ForecastItem {
  description: string
  kind: CategoryKind
  categoryId: number | null
  amount: number
  schedule: ForecastSchedule
  startMonth: number
  /** MONTHLY: last month, December when null */
  endMonth: number | null
}

export interface ForecastInput {
  name: string
  year: number
  /** Percent over the base: 3 for +3% */
  incomeGrowth: number
  expenseGrowth: number
  excludedTagIds: number[]
  excludedCategoryIds: number[]
  items: ForecastItem[]
}

export interface ForecastScenario extends ForecastInput {
  id: number
  updatedAt: string
}

export interface Forecast {
  baseCurrency: string
  year: number
  /** Base period, yyyy-MM: the year before once over, else the last twelve complete months */
  baseFrom: string
  baseTo: string
  months: { month: number; base: CashflowTotals; forecast: CashflowTotals }[]
  baseTotals: CashflowTotals
  totals: CashflowTotals
  /** What the percentages and the extra items add over the base */
  fromGrowth: CashflowTotals
  fromItems: CashflowTotals
  categories: { categoryId: number; name: string; color: string; kind: CategoryKind; base: number; forecast: number }[]
  /** Each item's total in the year, in the scenario's order */
  itemTotals: number[]
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

export type GoalKind = 'BALANCE' | 'YEARLY'
export type GoalState = 'REACHED' | 'ON_TRACK' | 'BEHIND' | 'IN_PROGRESS' | 'NO_RATE'

/** A savings goal with its progress in the base currency. */
export interface Goal {
  id: number
  name: string
  kind: GoalKind
  /** As entered, in the goal's own currency */
  targetAmount: number
  currency: string
  /** BALANCE only, optional (yyyy-MM-dd) */
  targetDate: string | null
  positionIds: number[]
  baseCurrency: string
  /** Target in the base currency; null when its currency cannot be converted (NO_RATE) */
  target: number | null
  /** BALANCE: value of the positions today; YEARLY: transferred into them this year */
  current: number
  remaining: number | null
  percent: number | null
  state: GoalState
  /** BALANCE: average monthly change over the last 6 months; YEARLY: per month so far this year */
  monthlyPace: number | null
  /** BALANCE: end of the month the target is reached at that pace */
  projectedDate: string | null
  /** Needed each month to make it in time (deadline or end of year) */
  requiredMonthly: number | null
  /** Months available, the current one included */
  monthsLeft: number | null
  /** YEARLY: the calendar year measured */
  year: number | null
  unconvertedCurrencies: string[]
}

/** A tag with the totals of its entries in the base currency, all dates. */
export interface TagSummary {
  id: number
  name: string
  entryCount: number
  income: number
  expense: number
  transferred: number
  /** Null for a tag without entries */
  firstDate: string | null
  lastDate: string | null
  /** Amounts per category in the base currency, largest first (transfers have none) */
  categories: TagCategoryAmount[]
}

export interface TagCategoryAmount {
  categoryId: number
  kind: EntryKind
  amount: number
}

export interface Tags {
  baseCurrency: string
  tags: TagSummary[]
  unconvertedCurrencies: string[]
}

// ---------------------------------------------------------------- annual report

export interface AnnualReport {
  baseCurrency: string
  year: number
  /** 31 December, or today for the current year */
  periodEnd: string
  /** Months in the period, for monthly averages */
  months: number
  availableYears: number[]
  totals: CashflowTotals
  /** 1 January to the same day one year earlier */
  previousTotals: CashflowTotals
  /** Income first, then expenses; largest first */
  categories: {
    categoryId: number
    name: string
    color: string
    kind: EntryKind
    amount: number
    previousAmount: number
    /** When any detail has entries; the macro's own entries under the macro's id */
    details: { categoryId: number; name: string; color: string; amount: number; previousAmount: number }[]
  }[]
  /** Value of every position on 31 December of the previous year */
  netWorthStart: number
  netWorthEnd: number
  classes: { assetClass: AssetClass; start: number; end: number }[]
  /** start/end null before the first value of the position or without an exchange rate */
  positions: {
    positionId: number
    name: string
    assetClass: AssetClass
    currency: string
    archived: boolean
    start: number | null
    end: number | null
    transfersIn: number
    transfersOut: number
  }[]
  tags: { tagId: number; name: string; entryCount: number; income: number; expense: number; transferred: number }[]
  largestExpenses: {
    entryId: number
    date: string
    categoryId: number | null
    description: string | null
    amount: number
    currency: string
    amountBase: number
  }[]
  unconvertedCurrencies: string[]
}

// ---------------------------------------------------------------- notifications

export interface NotificationSettings {
  /** False when the server has no SMTP settings: nothing can be sent */
  mailEnabled: boolean
  /** Confirmed address, the only one notifications go to */
  email: string | null
  /** Address waiting for the code sent to it */
  pendingEmail: string | null
  budgetAlerts: boolean
  goalAlerts: boolean
  monthlySummary: boolean
}

/** One category (a macro with its details) month by month in a year, against the year before */
export interface CategoryTrend {
  baseCurrency: string
  year: number
  categoryId: number
  name: string
  color: string
  kind: CategoryKind
  parentId: number | null
  /** The last month the year has had so far: 12 for a past year, the current month for this year */
  lastMonth: number
  /** The months of the year already over */
  completedMonths: number
  /** details: the year's amount per detail id (the macro's own entries under the macro's id) */
  months: { month: number; amount: number; previous: number; details: Record<string, number> }[]
  /** Largest first */
  details: { categoryId: number; name: string; amount: number; previous: number }[]
  total: number
  previousTotal: number
  /** The year up to today (all of a past year), and the year before up to the same day */
  toDate: number
  previousToDate: number
  availableYears: number[]
  unconvertedCurrencies: string[]
}
