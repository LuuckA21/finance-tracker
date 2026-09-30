import type { Category, CategoryKind, EntryKind, ImportRowError } from '../api/types'

/** Compared like the server does: ignoring case, accents and surrounding spaces */
export function foldName(name: string) {
  return name.normalize('NFD').replace(/\p{M}/gu, '').trim().toLowerCase()
}

const PATH = /^\s*([^›>]*[^›>\s])\s*[›>]\s*([^›>]*[^›>\s])\s*$/

/**
 * A category the file names but the user does not have, and the rows waiting for it:
 * {@code detail} under the existing macro {@code macroId}, or a new macro {@code macro}
 * (with a new {@code detail} under it when the file names one).
 */
export interface MissingCategory {
  key: string
  kind: CategoryKind
  macro: string
  macroId: number | null
  detail: string | null
  rows: number[]
}

interface Row {
  kind: EntryKind | null
  categoryId: number | null
  errors: ImportRowError[]
  raw: { category?: string; subcategory?: string }
}

/** The categories missing for the rows still without one, in the order the file first names them. */
export function missingCategories(rows: Row[], categories: Category[]): MissingCategory[] {
  const groups = new Map<string, MissingCategory>()
  rows.forEach((row, index) => {
    if (row.categoryId !== null || (row.kind !== 'INCOME' && row.kind !== 'EXPENSE')) return
    if (!row.errors.includes('unknown_category') && !row.errors.includes('unknown_subcategory')) return
    let name = (row.raw.category ?? '').trim()
    let detail: string | null = (row.raw.subcategory ?? '').trim() || null
    const path = detail === null ? PATH.exec(name) : null
    if (path) [name, detail] = [path[1], path[2]]
    if (!name) return
    const kind = row.kind
    const macro = categories.find((c) => c.parentId === null && c.kind === kind && foldName(c.name) === foldName(name))
    const key = [kind, foldName(name), detail === null ? '' : foldName(detail)].join('|')
    const group = groups.get(key) ?? { key, kind, macro: macro?.name ?? name, macroId: macro?.id ?? null, detail, rows: [] }
    group.rows.push(index)
    groups.set(key, group)
  })
  // A macro found meanwhile (created while reviewing) is no longer missing, unless a detail of it is
  return [...groups.values()].filter((g) => g.detail !== null || g.macroId === null)
}

/** Colours offered to a new macro category, the first one its kind does not use yet. */
const COLORS = ['#2563eb', '#ea580c', '#16a34a', '#9333ea', '#db2777', '#0891b2', '#d97706', '#4f46e5', '#dc2626', '#65a30d']

export function newMacroColor(categories: Category[], kind: CategoryKind) {
  const used = new Set(categories.filter((c) => c.kind === kind && c.parentId === null).map((c) => c.color.toLowerCase()))
  return COLORS.find((c) => !used.has(c)) ?? COLORS[0]
}
