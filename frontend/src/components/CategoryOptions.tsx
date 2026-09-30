import type { Category, CategoryKind } from '../api/types'
import { useI18n } from '../i18n'
import { categoryTree, PATH_SEPARATOR } from '../lib/categories'

/**
 * The options of a category select: each macro followed by its details, named with their macro
 * ("Casa › Affitto") so the closed select says which one is chosen. Without a kind, expense and
 * income categories come in two groups.
 */
export function CategoryOptions({ categories, kind, disabled }: {
  categories: Category[]
  kind?: CategoryKind
  /** Shown but not selectable */
  disabled?: (category: Category) => boolean
}) {
  const { t } = useI18n()
  const names = new Map(categories.map((c) => [c.id, c.name]))
  const options = (ofKind: CategoryKind | undefined) => categoryTree(categories, ofKind).map(({ category, depth }) => (
    <option key={category.id} value={category.id} disabled={disabled?.(category)}>
      {depth === 0 ? category.name : `${names.get(category.parentId!) ?? ''}${PATH_SEPARATOR}${category.name}`}
    </option>
  ))
  if (kind) return options(kind)
  return (
    <>
      <optgroup label={t('categories.expense')}>{options('EXPENSE')}</optgroup>
      <optgroup label={t('categories.income')}>{options('INCOME')}</optgroup>
    </>
  )
}
