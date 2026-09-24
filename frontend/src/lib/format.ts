import type { AssetClass } from '../api/types'

const LOCALE = 'it-CH'

const moneyFormatters = new Map<string, Intl.NumberFormat>()

/** 12'345.67 CHF (Swiss Italian grouping). */
export function money(value: number | null | undefined, currency: string, digits = 2): string {
  if (value === null || value === undefined) return '—'
  const key = `${currency}:${digits}`
  let f = moneyFormatters.get(key)
  if (!f) {
    f = new Intl.NumberFormat(LOCALE, {
      style: 'currency',
      currency,
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
    })
    moneyFormatters.set(key, f)
  }
  return f.format(value)
}

/** Compact form for axis ticks: 950, 12.5k, 1.2M. */
export function compact(value: number): string {
  const abs = Math.abs(value)
  const fmt = (n: number) => new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 1 }).format(n)
  if (abs >= 1_000_000) return `${fmt(value / 1_000_000)}M`
  if (abs >= 10_000) return `${fmt(value / 1_000)}k`
  return fmt(value)
}

export function number(value: number | null | undefined, maxDigits = 8): string {
  if (value === null || value === undefined) return '—'
  return new Intl.NumberFormat(LOCALE, { maximumFractionDigits: maxDigits }).format(value)
}

export function percent(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  return `${new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 1 }).format(value)} %`
}

export function date(iso: string | null | undefined): string {
  if (!iso) return '—'
  const [y, m, d] = iso.slice(0, 10).split('-')
  return `${d}.${m}.${y}`
}

export function dateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  return new Intl.DateTimeFormat(LOCALE, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso))
}

export const MONTHS_SHORT = ['gen', 'feb', 'mar', 'apr', 'mag', 'giu', 'lug', 'ago', 'set', 'ott', 'nov', 'dic']
export const MONTHS = [
  'gennaio', 'febbraio', 'marzo', 'aprile', 'maggio', 'giugno',
  'luglio', 'agosto', 'settembre', 'ottobre', 'novembre', 'dicembre',
]

/** "2026-03" -> "mar 26" */
export function periodLabel(period: string): string {
  if (/^\d{4}$/.test(period)) return period
  const [y, m] = period.split('-')
  return `${MONTHS_SHORT[Number(m) - 1]} ${y.slice(2)}`
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

export const ASSET_CLASS_LABEL: Record<AssetClass, string> = {
  CASH: 'Conti e liquidità',
  CRYPTO: 'Crypto',
  ETF: 'ETF',
  STOCK: 'Azioni',
  BOND: 'Obbligazioni',
  FUND: 'Fondi',
  PENSION: 'Previdenza',
  COMMODITY: 'Materie prime',
  REAL_ESTATE: 'Immobili',
  OTHER: 'Altro',
}

export const ASSET_CLASSES = Object.keys(ASSET_CLASS_LABEL) as AssetClass[]

export const COMMON_CURRENCIES = ['CHF', 'EUR', 'USD', 'GBP', 'JPY', 'CAD', 'AUD', 'SEK', 'NOK', 'DKK', 'PLN', 'CZK']

/** Parses user input that may use a comma as decimal separator or apostrophes as grouping. */
export function parseDecimal(input: string): number | null {
  const cleaned = input.trim().replace(/['\s]/g, '').replace(',', '.')
  if (cleaned === '' || !/^-?\d*\.?\d+$/.test(cleaned)) return null
  const n = Number(cleaned)
  return Number.isFinite(n) ? n : null
}
