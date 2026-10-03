import { afterEach, describe, expect, it, vi } from 'vitest'
import { setLanguage } from '../i18n'
import {
  change, compact, date, formatIban, money, monthsAgo, number, parseDecimal, percent, periodLabel, signedMoney, signedPercent,
} from './format'

describe('parseDecimal', () => {
  it.each([
    ['12', 12],
    ['12.5', 12.5],
    ['12,5', 12.5],
    ["1'234.50", 1234.5],
    ['1’234.50', 1234.5],
    [' 1 234.5 ', 1234.5],
    ['.5', 0.5],
    ['-3', -3],
    ['0', 0],
  ])('reads %j as %d', (input, expected) => {
    expect(parseDecimal(input)).toBe(expected)
  })

  it.each(['', ' ', 'abc', '1.2.3', '1,234.50', '12a', '--1', '1e5', 'Infinity'])('rejects %j', (input) => {
    expect(parseDecimal(input)).toBeNull()
  })
})

/**
 * Grouping characters and the space after the currency code come from the ICU data of the engine
 * (Node here, the browser in the app) and differ between versions: compare them normalized, and
 * assert exactly only what this code adds itself (signs, the no-break space before %).
 */
const plain = (text: string) => text.replace(/[\u00a0\u202f\u2009]/g, ' ').replace(/’/g, "'")

describe('numbers in Swiss formats', () => {
  it('uses the currency code and Swiss grouping', () => {
    expect(plain(money(12345.5, 'CHF'))).toBe("CHF 12'345.50")
    expect(plain(money(12345.5, 'CHF', 0))).toBe("CHF 12'346")
    expect(money(null, 'CHF')).toBe('—')
    expect(number(0.12345678)).toBe('0.12345678')
  })

  it('keeps the percent sign on the same line as the number', () => {
    expect(percent(66.24)).toBe('66.2\u00a0%')
    expect(percent(undefined)).toBe('—')
  })

  it('puts a real minus sign in front of signed values', () => {
    expect(plain(signedMoney(-80, 'CHF'))).toBe('−CHF 80.00')
    expect(plain(signedMoney(12.5, 'CHF'))).toBe('+CHF 12.50')
    expect(plain(signedMoney(0, 'CHF'))).toBe('CHF 0.00')
    expect(signedPercent(-0.8)).toBe('−0.8\u00a0%')
    expect(signedPercent(null)).toBe('—')
  })

  it('shortens axis ticks', () => {
    expect(compact(950)).toBe('950')
    expect(compact(12_500)).toBe('12.5k')
    expect(compact(-1_234_567)).toBe('-1.2M')
  })

  it('follows the interface language', async () => {
    await setLanguage('FR')
    // The amount before the currency code; the thousands separator is a space up to ICU 78.2 and an
    // apostrophe from 78.3 (Node 22.23.3), so either is accepted
    expect(plain(money(12345.5, 'CHF'))).toMatch(/^12[ ']345\.50 CHF$/)
    await setLanguage('DE')
    expect(plain(money(12345.5, 'CHF'))).toBe("CHF 12'345.50")
  })
})

describe('change', () => {
  it('gives the difference and the percentage of the previous value', () => {
    expect(change(110, 100)).toEqual({ amount: 10, percent: 10 })
    expect(change(80, 100)).toEqual({ amount: -20, percent: -20 })
  })

  it('measures against the size of a negative previous value', () => {
    expect(change(-50, -100)).toEqual({ amount: 50, percent: 50 })
  })

  it('has no percentage when there is nothing to compare with', () => {
    expect(change(5, 0)).toEqual({ amount: 5, percent: null })
  })
})

describe('dates', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('shows ISO dates as dd.MM.yyyy', () => {
    expect(date('2026-08-01')).toBe('01.08.2026')
    expect(date('2026-08-01T10:15:00Z')).toBe('01.08.2026')
    expect(date(null)).toBe('—')
  })

  it('labels periods with the month in the interface language', async () => {
    expect(periodLabel('2026')).toBe('2026')
    expect(periodLabel('2026-03')).toBe('mar 26')
    await setLanguage('EN')
    expect(periodLabel('2026-03')).toBe('Mar 26')
    await setLanguage('DE')
    expect(periodLabel('2026-12')).toBe('Dez 26')
  })

  it('counts months back without skipping short months', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date(2026, 2, 31, 12)) // 31 March: "one month ago" is February, not March 3
    expect(monthsAgo(0)).toBe('2026-03')
    expect(monthsAgo(1)).toBe('2026-02')
    expect(monthsAgo(3)).toBe('2025-12')
    expect(monthsAgo(15)).toBe('2024-12')
  })
})

describe('formatIban', () => {
  it('groups by four, whatever the spacing and case', () => {
    expect(formatIban(' ch9300762011623852957 ')).toBe('CH93 0076 2011 6238 5295 7')
    expect(formatIban('DE89 3704 0044 0532 0130 00')).toBe('DE89 3704 0044 0532 0130 00')
  })
})
