import { describe, expect, it } from 'vitest'
import { de } from './de'
import { en } from './en'
import { fr } from './fr'
import { getLanguage, getLocale, hasMessage, setLanguage, translate, type Language, type MessageKey } from '.'
import { it as italian } from './it'

const CATALOGS: Record<Language, Record<string, string>> = { IT: italian, EN: en, DE: de, FR: fr }
const placeholders = (text: string) => [...text.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).toSorted()

describe('catalogues', () => {
  const keys = Object.keys(italian) as MessageKey[]

  it.each(['EN', 'DE', 'FR'] as const)('%s has exactly the Italian keys', (language) => {
    expect(Object.keys(CATALOGS[language]).toSorted()).toEqual(keys.toSorted())
  })

  // A translation that loses {count} or {date} silently hides the value from the user
  it.each(['EN', 'DE', 'FR'] as const)('%s keeps every placeholder of the Italian text', (language) => {
    const mismatches = keys.filter((key) => placeholders(CATALOGS[language][key]).join() !== placeholders(italian[key]).join())
    expect(mismatches).toEqual([])
  })

  it.each(['IT', 'EN', 'DE', 'FR'] as const)('%s has no empty or padded texts', (language) => {
    const bad = Object.entries(CATALOGS[language]).filter(([, text]) => text.trim() === '' || text !== text.trim())
    expect(bad).toEqual([])
  })

  it('German uses Swiss spelling', () => {
    expect(Object.entries(de).filter(([, text]) => text.includes('ß'))).toEqual([])
  })

  it('French puts non-breaking spaces before : ; ? ! and inside guillemets', () => {
    const bad = Object.entries(fr).filter(([, text]) => / [:;?!]/.test(text) || /« | »/.test(text))
    expect(bad).toEqual([])
  })
})

describe('translate', () => {
  it('fills placeholders and leaves unknown ones visible', () => {
    expect(translate('import.selected', { count: 3, total: 10 })).toBe('Selezionate: 3 di 10')
    expect(translate('import.selected', { count: 3 })).toBe('Selezionate: 3 di {total}')
    expect(translate('common.save')).toBe('Salva')
  })

  it('switches catalogue, locale and document language together', async () => {
    await setLanguage('DE')
    expect(getLanguage()).toBe('DE')
    expect(getLocale()).toBe('de-CH')
    expect(translate('common.save')).toBe('Speichern')
    expect(document.documentElement.lang).toBe('de')
    expect(document.title).toBe('Finanzen')
  })

  it('applies the last language requested when switches overlap', async () => {
    const first = setLanguage('FR')
    const second = setLanguage('EN')
    await Promise.all([first, second])
    expect(getLanguage()).toBe('EN')
    expect(translate('common.save')).toBe('Save')
  })

  it('tells which runtime keys exist', () => {
    expect(hasMessage('error.invalid_credentials')).toBe(true)
    expect(hasMessage('error.no_such_code')).toBe(false)
  })
})
