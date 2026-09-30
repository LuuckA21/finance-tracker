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
