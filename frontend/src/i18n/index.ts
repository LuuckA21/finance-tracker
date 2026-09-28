import { useSyncExternalStore } from 'react'
import { de } from './de'
import { en } from './en'
import { fr } from './fr'
import { it } from './it'

/** Interface languages, spelled like the backend enum. */
export type Language = 'IT' | 'EN' | 'DE' | 'FR'
export const LANGUAGES: Language[] = ['IT', 'EN', 'DE', 'FR']

/** Each language named in itself, as shown in language pickers. */
export const LANGUAGE_NAMES: Record<Language, string> = { IT: 'Italiano', EN: 'English', DE: 'Deutsch', FR: 'Français' }

export type MessageKey = keyof typeof it
export type Messages = Record<MessageKey, string>
type Vars = Record<string, string | number>

const CATALOGS: Record<Language, Messages> = { IT: it, EN: en, DE: de, FR: fr }
const LOCALES: Record<Language, string> = { IT: 'it-CH', EN: 'en-CH', DE: 'de-CH', FR: 'fr-CH' }

let current: Language = 'IT'
const listeners = new Set<() => void>()

export function getLanguage(): Language {
  return current
}

export function setLanguage(language: Language) {
  document.documentElement.lang = language.toLowerCase()
  document.title = CATALOGS[language]['app.name']
  if (language === current) return
  current = language
  listeners.forEach((l) => l())
}

/** Intl locale of the current language (Swiss conventions: 12’345.67). */
export function getLocale(): string {
  return LOCALES[current]
}

/** Message in the current language; `{name}` placeholders are replaced from `vars`. */
export function translate(key: MessageKey, vars?: Vars): string {
  const text = CATALOGS[current][key]
  if (!vars) return text
  return text.replace(/\{(\w+)\}/g, (match, name: string) => (name in vars ? String(vars[name]) : match))
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** Re-renders the component when the language changes. */
export function useI18n() {
  const language = useSyncExternalStore(subscribe, getLanguage, getLanguage)
  return { language, locale: LOCALES[language], t: translate }
}
