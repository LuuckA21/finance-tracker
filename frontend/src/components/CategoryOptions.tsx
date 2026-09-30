import type { Category, CategoryKind } from '../api/types'
import { categoryTree, PATH_SEPARATOR } from '../lib/categories'

/**
 * The options of a category select: each macro followed by its details, named with their macro
 * ("Casa › Affitto") so the closed select says which one is chosen.
 */
export function CategoryOptions({ categories, kind, disabled }: {
  categories: Category[]
  kind?: CategoryKind
  /** Shown but not selectable */
  disabled?: (category: Category) => boolean
}) {
  const names = new Map(categories.map((c) => [c.id, c.name]))
  return categoryTree(categories, kind).map(({ category, depth }) => (
    <option key={category.id} value={category.id} disabled={disabled?.(category)}>
      {depth === 0 ? category.name : `${names.get(category.parentId!) ?? ''}${PATH_SEPARATOR}${category.name}`}
    </option>
  ))
}
