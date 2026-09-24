import { useSyncExternalStore } from 'react'
import type { AssetClass } from '../api/types'

/**
 * Chart palette (validated categorical order, light and dark steps chosen separately).
 * Charts read hex values from here because SVG attributes cannot rely on CSS variables everywhere.
 */
const LIGHT = {
  surface: '#fcfcfb',
  ink: '#0b0b0b',
  ink2: '#52514e',
  muted: '#898781',
  grid: '#e1e0d9',
  axis: '#c3c2b7',
  series: ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4', '#008300', '#4a3aa7', '#e34948'],
  other: '#a3a29c',
}

const DARK = {
  surface: '#1a1a19',
  ink: '#ffffff',
  ink2: '#c3c2b7',
  muted: '#898781',
  grid: '#2c2c2a',
  axis: '#383835',
  series: ['#3987e5', '#d95926', '#199e70', '#c98500', '#d55181', '#008300', '#9085e9', '#e66767'],
  other: '#6f6e69',
}

export type ChartTheme = typeof LIGHT

const query = '(prefers-color-scheme: dark)'

function subscribe(callback: () => void) {
  const mql = window.matchMedia(query)
  mql.addEventListener('change', callback)
  return () => mql.removeEventListener('change', callback)
}

export function useChartTheme(): ChartTheme {
  const dark = useSyncExternalStore(subscribe, () => window.matchMedia(query).matches, () => false)
  return dark ? DARK : LIGHT
}

/**
 * Colour follows the entity, never its rank: each asset class owns a fixed slot,
 * so filtering never repaints the remaining series. The two least common classes share "other" grey.
 */
const CLASS_SLOT: Record<AssetClass, number | null> = {
  CASH: 0,
  ETF: 1,
  STOCK: 2,
  CRYPTO: 3,
  PENSION: 4,
  BOND: 5,
  FUND: 6,
  REAL_ESTATE: 7,
  COMMODITY: null,
  OTHER: null,
}

/** Asset classes in slot order: stacks and legends follow it so adjacent colours stay distinguishable. */
export const CLASS_ORDER = (Object.keys(CLASS_SLOT) as AssetClass[]).sort(
  (a, b) => (CLASS_SLOT[a] ?? 99) - (CLASS_SLOT[b] ?? 99),
)

export function assetClassColor(theme: ChartTheme, assetClass: AssetClass): string {
  const slot = CLASS_SLOT[assetClass]
  return slot === null ? theme.other : theme.series[slot]
}

/** Income / expense use the first two categorical slots (not the reserved status colours). */
export function flowColors(theme: ChartTheme) {
  return { income: theme.series[0], expense: theme.series[1] }
}
