import type { AssetClass } from '../api/types'
import { getLocale, translate, type MessageKey } from '../i18n'

// Formatters follow the current interface language (both locales use Swiss grouping).
const formatters = new Map<string, Intl.NumberFormat>()

function numberFormat(options: Intl.NumberFormatOptions): Intl.NumberFormat {
  const key = `${getLocale()}:${JSON.stringify(options)}`
  let f = formatters.get(key)
  if (!f) {
    f = new Intl.NumberFormat(getLocale(), options)
    formatters.set(key, f)
  }
  return f
}

/** 12'345.67 CHF (Swiss grouping). */
export function money(value: number | null | undefined, currency: string, digits = 2): string {
  if (value === null || value === undefined) return '—'
  return numberFormat({
    style: 'currency',
    currency,
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).format(value)
}

/** Compact form for axis ticks: 950, 12.5k, 1.2M. */
export function compact(value: number): string {
  const abs = Math.abs(value)
  const fmt = (n: number) => numberFormat({ maximumFractionDigits: 1 }).format(n)
  if (abs >= 1_000_000) return `${fmt(value / 1_000_000)}M`
  if (abs >= 10_000) return `${fmt(value / 1_000)}k`
  return fmt(value)
}

/** Money with an explicit sign in front, whatever the locale puts the symbol: +CHF 1'234.50, −CHF 80.00. */
export function signedMoney(value: number, currency: string): string {
  const sign = value > 0 ? '+' : value < 0 ? '−' : ''
  return sign + money(Math.abs(value), currency)
}

/** Percentage with an explicit sign: +2.4 %, −0.8 %. */
export function signedPercent(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  const sign = value > 0 ? '+' : value < 0 ? '−' : ''
  return sign + percent(Math.abs(value))
}

/** Change from `previous` to `current`; the percentage is null when there is nothing to compare with. */
export function change(current: number, previous: number): { amount: number; percent: number | null } {
  const amount = current - previous
  return { amount, percent: previous === 0 ? null : (amount / Math.abs(previous)) * 100 }
}

export function number(value: number | null | undefined, maxDigits = 8): string {
  if (value === null || value === undefined) return '—'
  return numberFormat({ maximumFractionDigits: maxDigits }).format(value)
}

export function percent(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  return `${numberFormat({ maximumFractionDigits: 1 }).format(value)} %`
}

export function date(iso: string | null | undefined): string {
  if (!iso) return '—'
  const [y, m, d] = iso.slice(0, 10).split('-')
  return `${d}.${m}.${y}`
}

export function dateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  return new Intl.DateTimeFormat(getLocale(), { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso))
}

function monthNames(style: 'long' | 'short'): string[] {
  const f = new Intl.DateTimeFormat(getLocale(), { month: style, timeZone: 'UTC' })
  return Array.from({ length: 12 }, (_, i) => f.format(new Date(Date.UTC(2000, i, 1))).replace('.', ''))
}

/** Month name, 0-based: "marzo" / "March". */
export function monthName(index: number): string {
  return monthNames('long')[index]
}

/** Short month name, 0-based: "mar" / "Mar". */
export function monthShort(index: number): string {
  return monthNames('short')[index]
}

/** "2026-03" -> "mar 26" */
export function periodLabel(period: string): string {
  if (/^\d{4}$/.test(period)) return period
  const [y, m] = period.split('-')
  return `${monthShort(Number(m) - 1)} ${y.slice(2)}`
}

/** Local date as yyyy-MM-dd. */
export function today(): string {
  const d = new Date()
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${mm}-${dd}`
}

export function monthsAgo(n: number): string {
  const d = new Date()
  d.setDate(1)
  d.setMonth(d.getMonth() - n)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

export const ASSET_CLASSES: AssetClass[] = [
  'CASH', 'CRYPTO', 'ETF', 'STOCK', 'BOND', 'FUND', 'PENSION', 'COMMODITY', 'REAL_ESTATE', 'OTHER',
]

export function assetClassLabel(assetClass: AssetClass): string {
  return translate(`assetClass.${assetClass}` as MessageKey)
}

export const COMMON_CURRENCIES = ['CHF', 'EUR', 'USD', 'GBP', 'JPY', 'CAD', 'AUD', 'SEK', 'NOK', 'DKK', 'PLN', 'CZK']

/** Parses user input that may use a comma as decimal separator or apostrophes as grouping. */
export function parseDecimal(input: string): number | null {
  const cleaned = input.trim().replace(/['’\s]/g, '').replace(',', '.')
  if (cleaned === '' || !/^-?\d*\.?\d+$/.test(cleaned)) return null
  const n = Number(cleaned)
  return Number.isFinite(n) ? n : null
}
