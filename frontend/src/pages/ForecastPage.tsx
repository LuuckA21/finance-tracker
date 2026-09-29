import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Pencil, Plus, Trash2 } from 'lucide-react'
import { ApiError, errorMessage } from '../api/client'
import { useCategories, useDeleteForecast, useForecastPreview, useForecasts, useMe, useSaveForecast, useTags } from '../api/hooks'
import type { ForecastInput, ForecastItem, ForecastScenario } from '../api/types'
import { ForecastResult } from '../components/ForecastResult'
import { Badge, Button, Card, ErrorAlert, Field, Modal, PageHeader, Spinner } from '../components/ui'
import { useI18n } from '../i18n'
import { money, monthName, monthShort, parseDecimal, signedMoney } from '../lib/format'
import { ForecastItemForm } from './ForecastItemForm'

/** A scenario as edited: the percentages as typed. */
interface Draft extends Omit<ForecastInput, 'incomeGrowth' | 'expenseGrowth'> {
  incomeGrowth: string
  expenseGrowth: string
}

const blank = (): Draft => ({
  name: '', year: new Date().getFullYear() + 1, incomeGrowth: '0', expenseGrowth: '0',
  excludedTagIds: [], excludedCategoryIds: [], items: [],
})

const toDraft = (s: ForecastScenario): Draft => ({
  name: s.name, year: s.year, incomeGrowth: String(s.incomeGrowth), expenseGrowth: String(s.expenseGrowth),
  excludedTagIds: s.excludedTagIds, excludedCategoryIds: s.excludedCategoryIds,
  items: s.items.map((i) => ({ ...i, amount: Number(i.amount) })),
})

/** What the API takes; null while a percentage cannot be read. */
function toInput(d: Draft): ForecastInput | null {
  const incomeGrowth = parseDecimal(d.incomeGrowth)
  const expenseGrowth = parseDecimal(d.expenseGrowth)
  if (incomeGrowth === null || expenseGrowth === null) return null
  return { ...d, name: d.name.trim(), incomeGrowth, expenseGrowth }
}

/** The value once it has not changed for `ms`: the preview follows the typing without a request per key. */
function useDebounced(value: string | null, ms: number) {
  const [settled, setSettled] = useState(value)
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), ms)
    return () => clearTimeout(timer)
  }, [value, ms])
  return settled
}

/** A negative amount (something taken away) with its sign in front: −CHF 70.00 */
const amount = (value: number, currency: string, digits?: number) =>
  value < 0 ? signedMoney(value, currency, digits) : money(value, currency, digits)

/** "2025-09" → "set 2025" */
const shortMonth = (yearMonth: string) => `${monthShort(Number(yearMonth.slice(5, 7)) - 1)} ${yearMonth.slice(0, 4)}`

/** Income and expenses of a coming year, from saved scenarios. */
export function ForecastPage() {
  const { t } = useI18n()
  const scenarios = useForecasts()
  const [selected, setSelected] = useState<number | 'new' | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const list = scenarios.data ?? []
  const choose = (next: number | 'new' | null) => {
    setSelected(next)
    setNotice(null)
  }
  // The chosen scenario, else the first one, else a new one
  const effective = scenarios.isPending ? undefined
    : selected === 'new' ? 'new'
      : list.find((s) => s.id === selected)?.id ?? list[0]?.id ?? 'new'
  const base = useMe().data?.baseCurrency ?? 'CHF'

  return (
    <>
      <PageHeader title={t('forecast.title')} subtitle={t('forecast.subtitle', { currency: base })}
        actions={
          <>
            {list.length > 0 && effective !== undefined && (
              <select className="input w-auto max-w-[16rem]" aria-label={t('forecast.scenario')} value={effective}
                onChange={(e) => choose(e.target.value === 'new' ? 'new' : Number(e.target.value))}>
                {list.map((s) => <option key={s.id} value={s.id}>{s.name} ({s.year})</option>)}
                {effective === 'new' && <option value="new">{t('forecast.new')}</option>}
              </select>
            )}
            <Button variant="primary" onClick={() => choose('new')}><Plus className="size-4" /> {t('forecast.new')}</Button>
          </>
        } />
      {notice && <p role="status" className="mb-4 text-sm text-good">{notice}</p>}
      {effective === undefined ? <Spinner /> : (
        <ScenarioEditor key={String(effective)} saved={list.find((s) => s.id === effective) ?? null} first={list.length === 0}
          currency={base} onEdit={() => setNotice(null)}
          onSaved={(s) => {
            setSelected(s.id)
            setNotice(t('forecast.saved'))
          }}
          onDeleted={() => choose(null)} />
      )}
    </>
  )
}

function ScenarioEditor({ saved, first, currency, onEdit, onSaved, onDeleted }: {
  saved: ForecastScenario | null
  first: boolean
  currency: string
  onEdit: () => void
  onSaved: (scenario: ForecastScenario) => void
  onDeleted: () => void
}) {
  const { t } = useI18n()
  const categories = useCategories().data ?? []
  const tags = useTags().data?.tags ?? []
  const save = useSaveForecast()
  const remove = useDeleteForecast()
  const [draft, setDraft] = useState<Draft>(() => (saved ? toDraft(saved) : blank()))
  const [editing, setEditing] = useState<number | 'new' | null>(null)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)
  const update = (patch: Partial<Draft>) => {
    setDraft((d) => ({ ...d, ...patch }))
    onEdit()
  }

  const input = useMemo(() => toInput(draft), [draft])
  const key = input ? JSON.stringify(input) : null
  const settled = useDebounced(key, 400)
  const preview = useForecastPreview(useMemo(() => (settled ? JSON.parse(settled) as ForecastInput : null), [settled]))
  const dirty = !saved || key !== JSON.stringify(toInput(toDraft(saved)))
  const byId = new Map(categories.map((c) => [c.id, c]))
  const [thisYear] = useState(() => new Date().getFullYear())
  const years = [...new Set([...Array.from({ length: 6 }, (_, i) => thisYear + i), draft.year])].toSorted((a, b) => a - b)

  function toggle(list: 'excludedTagIds' | 'excludedCategoryIds', id: number) {
    const current = draft[list]
    update({ [list]: current.includes(id) ? current.filter((x) => x !== id) : [...current, id] })
  }

  function saveItem(item: ForecastItem) {
    update({ items: editing === 'new' ? [...draft.items, item] : draft.items.map((it, i) => (i === editing ? item : it)) })
    setEditing(null)
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    const found: Record<string, string> = {}
    if (!draft.name.trim()) found.name = t('forecast.nameRequired')
    if (parseDecimal(draft.incomeGrowth) === null) found.incomeGrowth = t('forecast.growthInvalid')
    if (parseDecimal(draft.expenseGrowth) === null) found.expenseGrowth = t('forecast.growthInvalid')
    setErrors(found)
    if (Object.keys(found).length > 0 || !input) return
    try {
      onSaved(await save.mutateAsync({ id: saved?.id, ...input }))
    } catch (err) {
      setError(errorMessage(err))
      if (err instanceof ApiError && err.fieldErrors) setErrors(err.fieldErrors)
    }
  }

  async function onDelete() {
    if (!saved || !confirm(t('forecast.confirmDelete', { name: saved.name }))) return
    setError(null)
    try {
      await remove.mutateAsync(saved.id)
      onDeleted()
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  const when = (item: ForecastItem) => item.schedule === 'ONCE'
    ? t('forecast.whenOnce', { month: monthName(item.startMonth - 1) })
    : t('forecast.whenMonthly', { from: monthShort(item.startMonth - 1), to: monthShort((item.endMonth ?? 12) - 1) })
  const excluded = draft.excludedTagIds.length + draft.excludedCategoryIds.length

  return (
    <>
      <Card title={t('forecast.scenario')} className="mb-4" actions={dirty && <Badge tone="accent">{t('forecast.unsaved')}</Badge>}>
        {first && <p className="mb-4 text-sm text-ink-2">{t('forecast.emptyHelp')}</p>}
        <form onSubmit={submit} className="flex flex-col gap-4">
          <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
            <Field label={t('common.name')} error={errors.name}>
              {(id) => <input id={id} className="input" maxLength={100} placeholder={t('forecast.namePlaceholder')} value={draft.name} onChange={(e) => update({ name: e.target.value })} />}
            </Field>
            <Field label={t('forecast.year')}>
              {(id) => (
                <select id={id} className="input" value={draft.year} onChange={(e) => update({ year: Number(e.target.value) })}>
                  {years.map((y) => <option key={y} value={y}>{y}</option>)}
                </select>
              )}
            </Field>
            <Field label={t('forecast.incomeGrowth')} error={errors.incomeGrowth}>
              {(id) => <input id={id} className="input tabular" inputMode="decimal" value={draft.incomeGrowth} onChange={(e) => update({ incomeGrowth: e.target.value })} />}
            </Field>
            <Field label={t('forecast.expenseGrowth')} error={errors.expenseGrowth}>
              {(id) => <input id={id} className="input tabular" inputMode="decimal" value={draft.expenseGrowth} onChange={(e) => update({ expenseGrowth: e.target.value })} />}
            </Field>
          </div>
          {preview.data && (
            <p className="text-xs text-muted">
              {t('forecast.base', { from: shortMonth(preview.data.baseFrom), to: shortMonth(preview.data.baseTo) })} {t('forecast.baseHelp')}
            </p>
          )}

          <details className="rounded-lg border border-line p-3" open={excluded > 0 || undefined}>
            <summary className="cursor-pointer text-sm font-medium">{t('forecast.exclude', { count: excluded })}</summary>
            <p className="mt-2 text-xs text-muted">{t('forecast.excludeHelp')}</p>
            {tags.length > 0 && (
              <Chips label={t('tags.label')} options={tags.map((tag) => ({ id: tag.id, name: tag.name }))}
                selected={draft.excludedTagIds} onToggle={(id) => toggle('excludedTagIds', id)} />
            )}
            <Chips label={t('forecast.categories')} options={categories.map((c) => ({ id: c.id, name: c.name, color: c.color }))}
              selected={draft.excludedCategoryIds} onToggle={(id) => toggle('excludedCategoryIds', id)} />
          </details>

          <section aria-labelledby="forecast-items" className="flex flex-col gap-2">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h3 id="forecast-items" className="text-sm font-semibold">{t('forecast.items')}</h3>
              <Button onClick={() => setEditing('new')}><Plus className="size-4" /> {t('forecast.addItem')}</Button>
            </div>
            <p className="text-xs text-muted">{t('forecast.itemsHelp', { currency })}</p>
            {draft.items.length === 0 ? <p className="py-2 text-sm text-muted">{t('forecast.noItems')}</p> : (
              <div className="relative -mx-4 overflow-x-auto sm:mx-0">
                <table className="w-full min-w-[36rem] text-sm">
                  <thead>
                    <tr className="border-b border-line text-left text-xs text-muted">
                      <th scope="col" className="px-4 py-2 font-medium sm:px-2">{t('forecast.itemDescription')}</th>
                      <th scope="col" className="px-2 py-2 font-medium">{t('cashflow.colCategory')}</th>
                      <th scope="col" className="px-2 py-2 font-medium">{t('forecast.schedule')}</th>
                      <th scope="col" className="px-2 py-2 text-right font-medium">{t('common.amount')}</th>
                      <th scope="col" className="px-2 py-2 text-right font-medium">{t('forecast.inYear')}</th>
                      <th scope="col" className="px-2 py-2"><span className="sr-only">{t('common.edit')}</span></th>
                    </tr>
                  </thead>
                  <tbody>
                    {draft.items.map((item, i) => (
                      <tr key={i} className="border-b border-line last:border-0">
                        <td className="px-4 py-2 sm:px-2">
                          {item.description}
                          <span className="ml-2 text-xs text-muted">{item.kind === 'INCOME' ? t('entryForm.income') : t('entryForm.expense')}</span>
                        </td>
                        <td className="px-2 py-2 text-ink-2">{item.categoryId !== null ? byId.get(item.categoryId)?.name ?? '—' : '—'}</td>
                        <td className="whitespace-nowrap px-2 py-2 text-ink-2">{when(item)}</td>
                        <td className="tabular whitespace-nowrap px-2 py-2 text-right">{amount(item.amount, currency)}</td>
                        <td className="tabular whitespace-nowrap px-2 py-2 text-right text-ink-2">
                          {preview.data && !preview.isPlaceholderData ? amount(preview.data.itemTotals[i], currency, 0) : '…'}
                        </td>
                        <td className="whitespace-nowrap px-2 py-2 text-right">
                          <button type="button" className="rounded p-1.5 text-muted hover:bg-surface-2 hover:text-ink" aria-label={t('forecast.editItem', { name: item.description })} onClick={() => setEditing(i)}>
                            <Pencil className="size-4" />
                          </button>
                          <button type="button" className="rounded p-1.5 text-muted hover:bg-surface-2 hover:text-bad" aria-label={t('forecast.removeItem', { name: item.description })}
                            onClick={() => update({ items: draft.items.filter((_, j) => j !== i) })}>
                            <Trash2 className="size-4" />
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <ErrorAlert message={error} />
          <div className="flex justify-end gap-2">
            {saved && <Button variant="danger" onClick={onDelete} loading={remove.isPending}><Trash2 className="size-4" /> {t('common.delete')}</Button>}
            <Button type="submit" variant="primary" loading={save.isPending}>{t('common.save')}</Button>
          </div>
        </form>
      </Card>

      {preview.isError ? <ErrorAlert message={errorMessage(preview.error)} />
        : preview.data ? <ForecastResult forecast={preview.data} currency={preview.data.baseCurrency} /> : <Spinner />}

      <Modal title={editing === 'new' ? t('forecast.addItem') : t('forecast.editItemTitle')} open={editing !== null} onClose={() => setEditing(null)}>
        {editing !== null && (
          <ForecastItemForm item={editing === 'new' ? null : draft.items[editing]} categories={categories} currency={currency} onDone={saveItem} />
        )}
      </Modal>
    </>
  )
}

/** Toggle buttons for a set of tags or categories. */
function Chips({ label, options, selected, onToggle }: {
  label: string
  options: { id: number; name: string; color?: string }[]
  selected: number[]
  onToggle: (id: number) => void
}) {
  return (
    <div className="mt-3">
      <p className="mb-1.5 text-xs font-medium text-ink-2">{label}</p>
      <div className="flex flex-wrap gap-1.5" role="group" aria-label={label}>
        {options.map((o) => {
          const on = selected.includes(o.id)
          return (
            <button key={o.id} type="button" aria-pressed={on} onClick={() => onToggle(o.id)}
              className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs transition-colors ${on ? 'border-accent bg-accent-soft text-ink line-through' : 'border-line text-ink-2 hover:bg-surface-2'}`}>
              {o.color && <span className="size-2 rounded-full" style={{ background: o.color }} aria-hidden />}
              {o.name}
            </button>
          )
        })}
      </div>
    </div>
  )
}
