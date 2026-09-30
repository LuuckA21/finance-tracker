import { describe, expect, it } from 'vitest'
import type { Category, ImportRowError } from '../api/types'
import { foldName, missingCategories, newMacroColor } from './importCategories'

const categories: Category[] = [
  { id: 1, name: 'Casa', kind: 'EXPENSE', color: '#2563eb', parentId: null },
  { id: 2, name: 'Affitto', kind: 'EXPENSE', color: '#2563eb', parentId: 1 },
  { id: 3, name: 'Stipendio', kind: 'INCOME', color: '#16a34a', parentId: null },
]
const row = (category: string, subcategory = '', errors: ImportRowError[] = ['unknown_category'], kind: 'EXPENSE' | 'INCOME' | null = 'EXPENSE') =>
  ({ kind, categoryId: null, errors, raw: { category, subcategory } })

describe('missingCategories', () => {
  it('groups the rows by the category the file names, ignoring case and accents', () => {
    const groups = missingCategories([row('Ristorazione'), row('ristorazione '), row('Caffè'), row('CAFFE')], categories)
    expect(groups.map((g) => [g.macro, g.macroId, g.detail, g.rows])).toEqual([
      ['Ristorazione', null, null, [0, 1]], ['Caffè', null, null, [2, 3]],
    ])
  })

  it('finds a missing detail under a macro the user has, in its column or as a path', () => {
    const groups = missingCategories([row('casa', 'Garage', ['unknown_subcategory']), row('Casa › garage', '', ['unknown_subcategory'])], categories)
    expect(groups).toEqual([{ key: 'EXPENSE|casa|garage', kind: 'EXPENSE', macro: 'Casa', macroId: 1, detail: 'Garage', rows: [0, 1] }])
  })

  it('proposes a new macro with its detail when neither exists', () => {
    const [group] = missingCategories([row('Animali', 'Veterinario')], categories)
    expect([group.macro, group.macroId, group.detail]).toEqual(['Animali', null, 'Veterinario'])
  })

  it('keeps kinds apart and leaves out rows that are done, transfers and other problems', () => {
    const groups = missingCategories([
      row('Regali'), row('Regali', '', ['unknown_category'], 'INCOME'),
      { ...row('Altro'), categoryId: 1 }, row('Altro', '', ['unknown_category'], null),
      row('Casa', '', ['category_kind_mismatch']),
    ], categories)
    expect(groups.map((g) => g.key)).toEqual(['EXPENSE|regali|', 'INCOME|regali|'])
  })
})

describe('helpers', () => {
  it('folds names like the server', () => {
    expect(foldName('  Caffè ')).toBe('caffe')
  })

  it('offers a colour the kind does not use yet', () => {
    expect(newMacroColor(categories, 'EXPENSE')).toBe('#ea580c')
    expect(newMacroColor(categories, 'INCOME')).toBe('#2563eb')
  })
})
