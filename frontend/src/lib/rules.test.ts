import { describe, expect, it } from 'vitest'
import { matchesPattern, normalizeText, patternFor } from './rules'

describe('rules', () => {
  it('normalizes like the server', () => {
    expect(normalizeText('  Café-de la GARE, #12! ')).toBe('cafe de la gare 12')
  })

  it('matches descriptions containing the text, case, accents and punctuation aside', () => {
    expect(matchesPattern('MIGROS ZÜRICH 1234', 'migros zurich')).toBe(true)
    expect(matchesPattern('Bar-Cafe centrale', 'café')).toBe(true)
    expect(matchesPattern('Coop', 'migros')).toBe(false)
    expect(matchesPattern(null, 'migros')).toBe(false)
    expect(matchesPattern('anything', ' ** ')).toBe(false)
  })

  it('proposes the words without digits', () => {
    expect(patternFor('MIGROS ZÜRICH 1234 * carta 12.08')).toBe('migros zurich carta')
    expect(patternFor('Pagamento carta Migros Bern Bahnhof')).toBe('pagamento carta migros bern')
    expect(patternFor('12.08.2026')).toBe('')
    expect(patternFor(null)).toBe('')
  })
})
