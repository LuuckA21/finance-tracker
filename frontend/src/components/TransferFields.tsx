import { ArrowLeftRight } from 'lucide-react'
import { usePositions } from '../api/hooks'
import type { Category, EntryKind, Position } from '../api/types'
import { PATH_SEPARATOR } from '../lib/categories'
import { useI18n } from '../i18n'
import { Field } from './ui'

/** Positions offered for a transfer: active ones, plus an archived one already selected. */
export function transferOptions(positions: Position[], selected: (number | null)[]) {
  return positions.filter((p) => !p.archived || selected.includes(p.id))
}

/** "From" and "To" of a transfer: both optional, among the user's own positions. */
export function TransferFields({ from, to, onChange, errors }: {
  from: number | null
  to: number | null
  onChange: (value: { from: number | null; to: number | null }) => void
  errors?: { from?: string; to?: string }
}) {
  const { t } = useI18n()
  const options = transferOptions(usePositions().data ?? [], [from, to])
  const select = (id: string, value: number | null, set: (v: number | null) => void) => (
    <select id={id} className="input" value={value ?? ''} onChange={(e) => set(e.target.value ? Number(e.target.value) : null)}>
      <option value="">{t('transfer.none')}</option>
      {options.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
    </select>
  )
  return (
    <>
      <Field label={t('transfer.from')} error={errors?.from} hint={t('transfer.optional')}>
        {(id) => select(id, from, (v) => onChange({ from: v, to }))}
      </Field>
      <Field label={t('transfer.to')} error={errors?.to} hint={t('transfer.optional')}>
        {(id) => select(id, to, (v) => onChange({ from, to: v }))}
      </Field>
    </>
  )
}

/** "Conto UBS → Viac 3a", or null when neither side is known. */
export function transferRoute(positions: Position[], from: number | null, to: number | null, unknown: string) {
  if (from === null && to === null) return null
  const name = (id: number | null) => (id === null ? unknown : positions.find((p) => p.id === id)?.name ?? unknown)
  return `${name(from)} → ${name(to)}`
}

/** Category of an income/expense, or "Transfer" with its route, for entry and rule lists. */
export function EntryTarget({ kind, categoryId, from, to, categories, positions }: {
  kind: EntryKind
  categoryId: number | null
  from: number | null
  to: number | null
  categories: Map<number, Category>
  positions: Position[]
}) {
  const { t } = useI18n()
  if (kind === 'TRANSFER') {
    const route = transferRoute(positions, from, to, '?')
    return (
      <span className="flex flex-col">
        <span className="flex items-center gap-2">
          <ArrowLeftRight className="size-3.5 text-accent" aria-hidden />
          {t('transfer.label')}
        </span>
        {route && <span className="text-xs text-muted">{route}</span>}
      </span>
    )
  }
  const category = categoryId === null ? undefined : categories.get(categoryId)
  const macro = category?.parentId == null ? undefined : categories.get(category.parentId)
  return (
    <span className="flex items-center gap-2">
      <span className="size-2.5 shrink-0 rounded-full" style={{ background: category?.color }} aria-hidden />
      <span>
        {macro && <span className="text-muted">{macro.name}{PATH_SEPARATOR}</span>}
        {category?.name ?? '—'}
      </span>
    </span>
  )
}

/** Tailwind class and sign for an amount: income +, expense −, transfer neutral. */
export function amountStyle(kind: EntryKind): { className: string; sign: string } {
  if (kind === 'INCOME') return { className: 'text-good', sign: '+ ' }
  if (kind === 'EXPENSE') return { className: 'text-ink', sign: '− ' }
  return { className: 'text-ink-2', sign: '' }
}
