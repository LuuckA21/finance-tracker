import { describe, expect, it } from 'vitest'
import type { Category } from '../api/types'
import { categoryPath, categoryTree, macros } from './categories'

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
