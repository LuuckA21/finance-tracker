import { describe, expect, it } from 'vitest'
import type { Category } from '../api/types'
import { barParts, categoryPath, categoryTree, macros } from './categories'

const categories: Category[] = [
  { id: 1, name: 'Svago', kind: 'EXPENSE', color: '#9333ea', parentId: null },
  { id: 2, name: 'Casa', kind: 'EXPENSE', color: '#2563eb', parentId: null },
  { id: 3, name: 'Energia', kind: 'EXPENSE', color: '#2563eb', parentId: 2 },
  { id: 4, name: 'Affitto', kind: 'EXPENSE', color: '#2563eb', parentId: 2 },
  { id: 5, name: 'Stipendio', kind: 'INCOME', color: '#16a34a', parentId: null },
]
const byId = new Map(categories.map((c) => [c.id, c]))

describe('categoryTree', () => {
  it('lists each macro followed by its details, by name', () => {
    expect(categoryTree(categories).map((n) => [n.category.name, n.depth])).toEqual([
      ['Casa', 0], ['Affitto', 1], ['Energia', 1], ['Stipendio', 0], ['Svago', 0],
    ])
  })

  it('keeps one kind only', () => {
    expect(categoryTree(categories, 'INCOME').map((n) => n.category.id)).toEqual([5])
    expect(macros(categories, 'EXPENSE').map((c) => c.id)).toEqual([2, 1])
  })
})

describe('categoryPath', () => {
  it('names the macro before a detail', () => {
    expect(categoryPath(4, byId)).toBe('Casa › Affitto')
    expect(categoryPath(2, byId)).toBe('Casa')
    expect(categoryPath(99, byId)).toBe('')
    expect(categoryPath(null, byId)).toBe('')
  })
})

const detail = (categoryId: number, name: string, amount: number) => ({ categoryId, name, amount, share: amount / 10 })

describe('barParts', () => {
  it('lists the details largest first, then the macro\'s own entries', () => {
    const parts = barParts(2, [detail(2, 'Casa', 40), detail(3, 'Energia', 90), detail(4, 'Affitto', 1800)], 4)
    expect(parts.map((p) => [p.key, p.role, p.step])).toEqual([['4', 'detail', 0], ['3', 'detail', 1], ['2', 'own', -1]])
  })

  it('folds the smallest details into one part past the limit', () => {
    const parts = barParts(1, [detail(2, 'a', 50), detail(3, 'b', 40), detail(4, 'c', 30), detail(5, 'd', 20), detail(6, 'e', 10)], 4)
    expect(parts.map((p) => [p.key, p.amount, p.count])).toEqual([['2', 50, 1], ['3', 40, 1], ['4', 30, 1], ['rest', 30, 2]])
    expect(parts[3].share).toBe(3)
    expect(parts[3].step).toBe(3)
    expect(parts[3].members.map((m) => m.name)).toEqual(['d', 'e'])
  })

  it('has nothing to split for a macro without details', () => {
    expect(barParts(2, [], 4)).toEqual([])
    expect(barParts(2, [detail(2, 'Casa', 40)], 4)).toEqual([])
  })
})
