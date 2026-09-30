import { useState, type FormEvent } from 'react'
import { ChevronLeft, ChevronRight, Pencil, Plus, Trash2 } from 'lucide-react'
import { ApiError, errorMessage } from '../api/client'
import { useBudgetStatus, useCategories, useDeleteBudget, useSaveBudget } from '../api/hooks'
import type { BudgetState, BudgetStatus, Category } from '../api/types'
import { CategoryOptions } from '../components/CategoryOptions'
import { Button, Card, EmptyState, ErrorAlert, Field, MissingRatesNotice, Modal, PageHeader, Spinner, StatTile } from '../components/ui'
import { useI18n } from '../i18n'
import { COMMON_CURRENCIES, money, monthName, parseDecimal, percent } from '../lib/format'

type Row = BudgetStatus['categories'][number]

/** Budget being created or edited; {@code categoryId} null = choose a category. */
interface Draft {
  categoryId: number | null
  amount: string
  currency: string
  /** Recent monthly average, in the base currency */
  average?: number
  base: string
  editing: boolean
}

const BAR: Record<BudgetState, string> = { OK: 'bg-good', WARNING: 'bg-warn-ink', OVER: 'bg-bad' }
const TEXT: Record<BudgetState, string> = { OK: 'text-good', WARNING: 'text-warn-ink', OVER: 'text-bad' }

function shiftMonth(month: string, delta: number) {
  const [y, m] = month.split('-').map(Number)
  const d = new Date(Date.UTC(y, m - 1 + delta, 1))
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`
}

/** Suggested budget from the recent average: rounded up to 10 (to 1 below 10). */
function suggestion(average: number) {
  if (average <= 0) return ''
  return String(average < 10 ? Math.ceil(average) : Math.ceil(average / 10) * 10)
}

export function BudgetPage() {
  const { t } = useI18n()
  const [month, setMonth] = useState<string | undefined>(undefined)
  const status = useBudgetStatus(month)
  const remove = useDeleteBudget()
  const [draft, setDraft] = useState<Draft | null>(null)
  const [error, setError] = useState<string | null>(null)
  const categories = useCategories().data ?? []
  const data = status.data
  const currency = data?.baseCurrency ?? 'CHF'
  const shown = month ?? data?.month
  const taken = data?.categories.map((c) => c.categoryId) ?? []
  const conflicted = (id: number) => {
    const category = categories.find((c) => c.id === id)
    return category !== undefined && budgetConflict(category, categories, taken)
  }

  async function onDelete(row: Row) {
    if (!confirm(t('budget.confirmDelete', { category: row.name }))) return
    setError(null)
    try {
      await remove.mutateAsync(row.categoryId)
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  const [year, monthIndex] = (shown ?? '2000-01').split('-').map(Number)

  return (
    <>
      <PageHeader
        title={t('budget.title')}
        subtitle={t('budget.subtitle', { currency })}
        actions={
          <>
            <div className="flex items-center gap-1">
              <Button variant="ghost" aria-label={t('budget.previousMonth')} disabled={!shown}
                onClick={() => shown && setMonth(shiftMonth(shown, -1))}>
                <ChevronLeft className="size-4" />
              </Button>
              <span className="min-w-36 text-center text-sm font-medium capitalize">
                {shown ? `${monthName(monthIndex - 1)} ${year}` : '…'}
              </span>
              <Button variant="ghost" aria-label={t('budget.nextMonth')} disabled={!shown}
                onClick={() => shown && setMonth(shiftMonth(shown, 1))}>
                <ChevronRight className="size-4" />
              </Button>
            </div>
            <Button variant="primary" onClick={() => setDraft({ categoryId: null, amount: '', currency, base: currency, editing: false })}>
              <Plus className="size-4" /> {t('budget.new')}
            </Button>
          </>
        }
      />
      <ErrorAlert message={error} />

      {status.isPending ? <Spinner /> : data && (
        <>
          <MissingRatesNotice currencies={data.unconvertedCurrencies} baseCurrency={currency} />
          <div className="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-4">
            <StatTile label={t('budget.total')} value={money(data.budgeted, currency, 0)} />
            <StatTile label={t('budget.spent')} value={money(data.spent, currency, 0)}
              sub={data.budgeted > 0 ? t('budget.ofBudget', { percent: percent((data.spent / data.budgeted) * 100) }) : undefined} />
            <StatTile label={t('budget.remaining')} value={money(data.remaining, currency, 0)} tone={data.remaining >= 0 ? 'good' : 'bad'} />
            <StatTile label={t('budget.unbudgeted')} value={money(data.unbudgeted, currency, 0)} sub={t('budget.unbudgetedHint')} />
          </div>

          <Card title={t('budget.categories')} className="mb-4">
            {data.categories.length === 0 ? (
              <EmptyState title={t('budget.emptyTitle')}>{t('budget.emptyHelp')}</EmptyState>
            ) : (
              <ul className="flex flex-col divide-y divide-line">
                {data.categories.map((row) => (
                  <BudgetRow key={row.categoryId} row={row} currency={currency} currentMonth={data.currentMonth}
                    onEdit={() => setDraft({ categoryId: row.categoryId, amount: String(row.amount), currency: row.currency, average: row.average, base: currency, editing: true })}
                    onDelete={() => onDelete(row)} />
                ))}
              </ul>
            )}
          </Card>

          {data.others.length > 0 && (
            <Card title={t('budget.others')}>
              <p className="mb-3 text-xs text-muted">{t('budget.othersHelp')}</p>
              <ul className="flex flex-col divide-y divide-line">
                {data.others.map((o) => (
                  <li key={o.categoryId} className="flex flex-wrap items-center justify-between gap-2 py-2.5 text-sm">
                    <span className="flex items-center gap-2 font-medium">
                      <span className="size-2.5 rounded-full" style={{ background: o.color }} aria-hidden />
                      {o.name}
                    </span>
                    <span className="flex flex-wrap items-center gap-3">
                      <span className="tabular">{money(o.spent, currency)}</span>
                      <span className="text-xs text-muted">{t('budget.average', { amount: money(o.average, currency) })}</span>
                      <Button onClick={() => setDraft({ categoryId: conflicted(o.categoryId) ? null : o.categoryId, amount: suggestion(o.average), currency, average: o.average, base: currency, editing: false })}>
                        {t('budget.set')}
                      </Button>
                    </span>
                  </li>
                ))}
              </ul>
            </Card>
          )}
        </>
      )}

      <Modal title={draft?.editing ? t('budget.edit') : t('budget.new')} open={draft !== null} onClose={() => setDraft(null)}>
        {draft && <BudgetForm draft={draft} taken={taken} onDone={() => setDraft(null)} />}
      </Modal>
    </>
  )
}

function BudgetRow({ row, currency, currentMonth, onEdit, onDelete }: {
  row: Row
  currency: string
  currentMonth: boolean
  onEdit: () => void
  onDelete: () => void
}) {
  const { t } = useI18n()
  const width = Math.min(row.percent ?? 0, 100)
  const overProjected = currentMonth && row.projected !== null && row.budget !== null && row.projected > row.budget
  return (
    <li className="py-3">
      <div className="mb-1.5 flex flex-wrap items-center justify-between gap-2">
        <span className="flex items-center gap-2 font-medium">
          <span className="size-2.5 rounded-full" style={{ background: row.color }} aria-hidden />
          {row.name}
          {row.state !== 'OK' && (
            <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${row.state === 'OVER' ? 'bg-bad-soft text-bad' : 'bg-warn-soft text-warn-ink'}`}>
              {row.state === 'OVER' ? t('budget.over') : t('budget.warning')}
            </span>
          )}
        </span>
        <span className="flex items-center gap-1">
          <span className="tabular text-sm">
            <strong>{money(row.spent, currency)}</strong>
            <span className="text-muted"> / {row.budget === null ? money(row.amount, row.currency) : money(row.budget, currency)}</span>
          </span>
          <button type="button" className="rounded p-1.5 text-muted hover:bg-surface-2 hover:text-ink" aria-label={t('budget.editCategory', { category: row.name })} onClick={onEdit}>
            <Pencil className="size-4" />
          </button>
          <button type="button" className="rounded p-1.5 text-muted hover:bg-surface-2 hover:text-bad" aria-label={t('budget.deleteCategory', { category: row.name })} onClick={onDelete}>
            <Trash2 className="size-4" />
          </button>
        </span>
      </div>
      <div className="h-2 overflow-hidden rounded-full bg-surface-2" role="progressbar" aria-label={row.name}
        aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(width)}>
        <div className={`h-full rounded-full ${BAR[row.state]}`} style={{ width: `${width}%` }} />
      </div>
      <div className="mt-1.5 flex flex-wrap justify-between gap-x-4 gap-y-1 text-xs text-ink-2">
        <span className={TEXT[row.state]}>
          {row.percent === null ? t('budget.noRate') : row.remaining !== null && row.remaining >= 0
            ? t('budget.left', { amount: money(row.remaining, currency), percent: percent(row.percent) })
            : t('budget.exceeded', { amount: money(Math.abs(row.remaining ?? 0), currency), percent: percent(row.percent) })}
        </span>
        <span className="flex flex-wrap gap-x-4">
          {currentMonth && row.projected !== null && (
            <span className={overProjected ? 'text-bad' : ''}>{t('budget.projected', { amount: money(row.projected, currency) })}</span>
          )}
          <span className="text-muted">{t('budget.average', { amount: money(row.average, currency) })}</span>
        </span>
      </div>
    </li>
  )
}

/**
 * A macro's budget covers its details, so a macro and its details never both have one: a macro is
 * out once a detail has a budget, a detail once its macro has.
 */
function budgetConflict(category: Category, categories: Category[], taken: number[]) {
  return category.parentId === null
    ? categories.some((c) => c.parentId === category.id && taken.includes(c.id))
    : taken.includes(category.parentId)
}

function BudgetForm({ draft, taken, onDone }: { draft: Draft; taken: number[]; onDone: () => void }) {
  const { t } = useI18n()
  const categories = (useCategories().data ?? []).filter((c) => c.kind === 'EXPENSE')
  const save = useSaveBudget()
  const [categoryId, setCategoryId] = useState<number | ''>(draft.categoryId ?? '')
  const [amount, setAmount] = useState(draft.amount)
  const [currency, setCurrency] = useState(draft.currency)
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    const parsed = parseDecimal(amount)
    if (parsed === null || parsed <= 0) {
      setFieldErrors({ amount: t('budget.amountRequired') })
      return
    }
    if (categoryId === '') {
      setFieldErrors({ categoryId: t('entryForm.categoryRequired') })
      return
    }
    setFieldErrors({})
    try {
      await save.mutateAsync({ categoryId, amount: parsed, currency: currency.toUpperCase() })
      onDone()
    } catch (err) {
      setError(errorMessage(err))
      if (err instanceof ApiError && err.fieldErrors) setFieldErrors(err.fieldErrors)
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4">
      <p className="text-sm text-ink-2">{t('budget.formHelp')}</p>
      <Field label={t('entries.category')} error={fieldErrors.categoryId}>
        {(id) => (
          <select id={id} className="input" required value={categoryId} disabled={draft.editing}
            onChange={(e) => setCategoryId(e.target.value ? Number(e.target.value) : '')}>
            <option value="">{t('entryForm.choose')}</option>
            <CategoryOptions categories={categories} kind="EXPENSE"
              disabled={(c) => c.id !== draft.categoryId && (taken.includes(c.id) || budgetConflict(c, categories, taken))} />
          </select>
        )}
      </Field>
      <div className="grid grid-cols-2 gap-3">
        <Field label={t('budget.monthlyAmount')} error={fieldErrors.amount}
          hint={draft.average ? t('budget.average', { amount: money(draft.average, draft.base) }) : undefined}>
          {(id) => (
            <input id={id} className="input tabular" inputMode="decimal" required placeholder="0.00" autoFocus
              value={amount} onChange={(e) => setAmount(e.target.value)} />
          )}
        </Field>
        <Field label={t('common.currency')} error={fieldErrors.currency}>
          {(id) => (
            <>
              <input id={id} className="input uppercase" list="budget-currencies" maxLength={3} required value={currency}
                onChange={(e) => setCurrency(e.target.value.toUpperCase())} />
              <datalist id="budget-currencies">{COMMON_CURRENCIES.map((c) => <option key={c} value={c}>{c}</option>)}</datalist>
            </>
          )}
        </Field>
      </div>
      <ErrorAlert message={error} />
      <div className="flex justify-end">
        <Button type="submit" variant="primary" loading={save.isPending}>{t('common.save')}</Button>
      </div>
    </form>
  )
}
