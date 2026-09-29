import type { Category, TagCategoryAmount } from '../api/types'
import { useI18n } from '../i18n'
import { money } from '../lib/format'
import { amountStyle } from './TransferFields'

/** How a tag's entries split across categories, largest first, in the base currency. */
export function TagCategories({ rows, categories, currency }: {
  rows: TagCategoryAmount[]
  categories: Map<number, Category>
  currency: string
}) {
  const { t } = useI18n()
  if (rows.length === 0) return null
  return (
    <ul className="flex flex-wrap gap-x-3 gap-y-1 text-xs" aria-label={t('tags.byCategory')}>
      {rows.map((row) => {
        const category = categories.get(row.categoryId)
        const style = amountStyle(row.kind)
        return (
          <li key={row.categoryId} className="inline-flex items-center gap-1.5">
            <span className="size-2.5 rounded-full" style={{ background: category?.color }} aria-hidden />
            <span className="text-ink-2">{category?.name ?? '—'}</span>
            <span className={`tabular ${style.className}`}>{style.sign}{money(row.amount, currency)}</span>
          </li>
        )
      })}
    </ul>
  )
}
