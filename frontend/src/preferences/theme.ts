import { useSyncExternalStore } from 'react'

/** Theme preference, spelled like the backend enum; SYSTEM follows the device. */
export type Theme = 'SYSTEM' | 'LIGHT' | 'DARK'
export const THEMES: Theme[] = ['SYSTEM', 'LIGHT', 'DARK']

const DARK_QUERY = '(prefers-color-scheme: dark)'

let current: Theme = 'SYSTEM'
const listeners = new Set<() => void>()

function systemIsDark(): boolean {
  return window.matchMedia(DARK_QUERY).matches
}

/** The palette actually shown. */
export function isDark(): boolean {
  return current === 'DARK' || (current === 'SYSTEM' && systemIsDark())
}

export function getTheme(): Theme {
  return current
}

/** index.css keys its dark palette off data-theme, falling back to the media query when absent. */
export function setTheme(theme: Theme) {
  current = theme
  const root = document.documentElement
  if (theme === 'SYSTEM') delete root.dataset.theme
  else root.dataset.theme = theme.toLowerCase()
  listeners.forEach((l) => l())
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  const mql = window.matchMedia(DARK_QUERY)
  mql.addEventListener('change', listener)
  return () => {
    listeners.delete(listener)
    mql.removeEventListener('change', listener)
  }
}

/** Re-renders on preference or device changes. */
export function useTheme() {
  const theme = useSyncExternalStore(subscribe, getTheme, getTheme)
  const dark = useSyncExternalStore(subscribe, isDark, () => false)
  return { theme, dark }
}
