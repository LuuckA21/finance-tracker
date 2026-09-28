import { useEffect } from 'react'
import type { Me } from '../api/types'
import { LANGUAGES, setLanguage, type Language } from '../i18n'
import { THEMES, setTheme, type Theme } from './theme'

/**
 * Language and theme live in the user's profile. The last values are also kept in this
 * browser, so the login page and the first paint after a reload already use them.
 */
const STORAGE_KEY = 'ft-preferences'

interface Stored {
  language?: Language
  theme?: Theme
}

function read(): Stored {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? (JSON.parse(raw) as Stored) : {}
  } catch {
    return {}
  }
}

function write(value: Stored) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(value))
  } catch {
    // Storage disabled (private mode): the profile still holds the preferences
  }
}

function browserLanguage(): Language {
  const code = navigator.language.slice(0, 2).toUpperCase() as Language
  return LANGUAGES.includes(code) ? code : 'EN'
}

/** Applies the preferences stored in this browser; wait for it before the first render. */
export async function initPreferences() {
  const stored = read()
  setTheme(stored.theme && THEMES.includes(stored.theme) ? stored.theme : 'SYSTEM')
  await setLanguage(stored.language && LANGUAGES.includes(stored.language) ? stored.language : browserLanguage())
}

/** Applies preferences (the language as soon as its catalogue is loaded) and remembers them in this browser. */
export function applyPreferences(prefs: { language: Language; theme: Theme }) {
  setTheme(prefs.theme)
  write(prefs)
  // A catalogue that fails to load (network lost) leaves the current language in place
  setLanguage(prefs.language).catch(() => {})
}

/** Keeps the interface in line with the logged-in user's saved preferences. */
export function useProfilePreferences(me: Me | null | undefined) {
  const language = me?.language
  const theme = me?.theme
  useEffect(() => {
    if (language && theme) applyPreferences({ language, theme })
  }, [language, theme])
}
