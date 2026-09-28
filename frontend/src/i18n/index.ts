import { useSyncExternalStore } from 'react'
import type { it } from './it'

/** Interface languages, spelled like the backend enum. */
export type Language = 'IT' | 'EN' | 'DE' | 'FR'
export const LANGUAGES: Language[] = ['IT', 'EN', 'DE', 'FR']

/** Each language named in itself, as shown in language pickers. */
export const LANGUAGE_NAMES: Record<Language, string> = { IT: 'Italiano', EN: 'English', DE: 'Deutsch', FR: 'Français' }

export type MessageKey = keyof typeof it
export type Messages = Record<MessageKey, string>
type Vars = Record<string, string | number>

/** Each catalogue is a separate chunk: only the languages actually used are downloaded. */
const LOADERS: Record<Language, () => Promise<Messages>> = {
  IT: () => import('./it').then((m) => m.it),
  EN: () => import('./en').then((m) => m.en),
  DE: () => import('./de').then((m) => m.de),
  FR: () => import('./fr').then((m) => m.fr),
}
const LOCALES: Record<Language, string> = { IT: 'it-CH', EN: 'en-CH', DE: 'de-CH', FR: 'fr-CH' }

const catalogs: Partial<Record<Language, Messages>> = {}
let current: Language = 'IT'
let requested: Language | null = null
const listeners = new Set<() => void>()

export function getLanguage(): Language {
  return current
}

/**
 * Switches the interface language once its catalogue is loaded. When several switches overlap,
 * the last one requested wins.
 */
export async function setLanguage(language: Language) {
  requested = language
  const catalog = (catalogs[language] ??= await LOADERS[language]())
  if (requested !== language) return
  document.documentElement.lang = language.toLowerCase()
  document.title = catalog['app.name']
  if (language === current) return
  current = language
  listeners.forEach((l) => l())
}

/** Intl locale of the current language (Swiss conventions: 12’345.67). */
export function getLocale(): string {
  return LOCALES[current]
}

/**
 * Message in the current language; `{name}` placeholders are replaced from `vars`.
 * The app renders only after the first `setLanguage` has loaded a catalogue.
 */
export function translate(key: MessageKey, vars?: Vars): string {
  const text = catalogs[current]![key]
  if (!vars) return text
  return text.replace(/\{(\w+)\}/g, (match, name: string) => (name in vars ? String(vars[name]) : match))
}

/** Whether a key built at runtime (e.g. from a backend error code) exists in the catalogues. */
export function hasMessage(key: string): key is MessageKey {
  return key in catalogs[current]!
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
