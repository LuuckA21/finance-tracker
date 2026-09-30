import type { Category, CategoryKind } from '../api/types'

/** Between a macro and a detail in a category's path, as the server writes it */
export const PATH_SEPARATOR = ' › '

const byName = (a: Category, b: Category) => a.name.localeCompare(b.name)

/**
 * The categories in their two levels: each macro by name, followed by its details by name.
 * Optionally of one kind only.
 */
export function categoryTree(categories: Category[], kind?: CategoryKind): { category: Category; depth: 0 | 1 }[] {
  const ofKind = kind ? categories.filter((c) => c.kind === kind) : categories
  const details = new Map<number, Category[]>()
  for (const c of ofKind) {
    if (c.parentId !== null) details.set(c.parentId, [...(details.get(c.parentId) ?? []), c])
  }
  const tops = ofKind.filter((c) => c.parentId === null).toSorted(byName)
  return tops.flatMap((macro) => [
    { category: macro, depth: 0 as const },
    ...(details.get(macro.id) ?? []).toSorted(byName).map((category) => ({ category, depth: 1 as const })),
  ])
}

/** "Casa › Affitto" for a detail, "Casa" for a macro, "" when unknown */
export function categoryPath(id: number | null | undefined, byId: Map<number, Category>): string {
  const category = id == null ? undefined : byId.get(id)
  if (!category) return ''
  const parent = category.parentId === null ? undefined : byId.get(category.parentId)
  return parent ? parent.name + PATH_SEPARATOR + category.name : category.name
}

/** The macro categories, by name */
export function macros(categories: Category[], kind?: CategoryKind): Category[] {
  return categories.filter((c) => c.parentId === null && (!kind || c.kind === kind)).toSorted(byName)
}

/** One part of a macro category's bar: a detail, the rest of the details together, or the macro's own entries. */
export interface BarPart {
  key: string
  /** A detail's name; empty for the macro's own entries and for the rest */
  name: string
  amount: number
  share: number | null
  role: 'detail' | 'rest' | 'own'
  /** Position among the parts that are details (the shade to paint them with), -1 for the macro's own entries */
  step: number
  /** Details folded into the rest */
  count: number
  /** For the rest, the details it holds, largest first */
  members: { key: string; name: string; amount: number; share: number | null }[]
}

/**
 * The parts of a macro category's bar, largest first: its details, those past the first
 * {@code maxDetails - 1} folded into one part when there are more than {@code maxDetails}, then the
 * macro's own entries (under the macro's id). None for a macro without details.
 */
export function barParts(macroId: number, details: { categoryId: number; name: string; amount: number; share: number | null }[],
  maxDetails: number): BarPart[] {
  const own = details.filter((d) => d.categoryId === macroId)
  const named = details.filter((d) => d.categoryId !== macroId).toSorted((a, b) => b.amount - a.amount)
  if (named.length === 0) return []
  const shown = named.length > maxDetails ? named.slice(0, maxDetails - 1) : named
  const rest = named.slice(shown.length)
  const parts: BarPart[] = shown.map((d, i) => ({
    key: String(d.categoryId), name: d.name, amount: d.amount, share: d.share, role: 'detail', step: i, count: 1, members: [],
  }))
  if (rest.length > 0) {
    const shares = rest.map((d) => d.share)
    parts.push({
      key: 'rest', name: '', amount: rest.reduce((sum, d) => sum + d.amount, 0),
      share: shares.includes(null) ? null : shares.reduce<number>((sum, s) => sum + (s ?? 0), 0),
      role: 'rest', step: shown.length, count: rest.length,
      members: rest.map((d) => ({ key: String(d.categoryId), name: d.name, amount: d.amount, share: d.share })),
    })
  }
  for (const d of own) {
    parts.push({ key: String(d.categoryId), name: '', amount: d.amount, share: d.share, role: 'own', step: -1, count: 0, members: [] })
  }
  return parts
}
