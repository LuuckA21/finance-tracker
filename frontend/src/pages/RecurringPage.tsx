import { useMemo, useState, type FormEvent } from 'react'
import { Pause, Pencil, Play, Plus, Trash2 } from 'lucide-react'
import { ApiError, errorMessage } from '../api/client'
import { useCategories, useDeleteRecurring, useMe, useRecurringEntries, useSaveRecurring } from '../api/hooks'
import type { EntryKind, Frequency, RecurringEntry } from '../api/types'
import { Badge, Button, Card, EmptyState, ErrorAlert, Field, Modal, PageHeader, Segmented, Spinner } from '../components/ui'
import { useI18n, type MessageKey } from '../i18n'
import { COMMON_CURRENCIES, date, money, parseDecimal, today } from '../lib/format'

const FREQUENCIES: Frequency[] = ['DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'FOUR_MONTHLY', 'SEMIANNUAL', 'YEARLY']

export function RecurringPage() {
  const { t } = useI18n()
  const rules = useRecurringEntries()
  const categories = useCategories().data ?? []
  const save = useSaveRecurring()
  const remove = useDeleteRecurring()
  const [editing, setEditing] = useState<RecurringEntry | null>(null)
  const [formOpen, setFormOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const byId = useMemo(() => new Map(categories.map((c) => [c.id, c])), [categories])

  async function toggle(rule: RecurringEntry) {
    setError(null)
    try {
      await save.mutateAsync({ ...rule, active: !rule.active })
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  async function onDelete(rule: RecurringEntry) {
    if (!confirm(t('recurring.confirmDelete'))) return
    setError(null)
    try {
      await remove.mutateAsync(rule.id)
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  const list = rules.data ?? []

  return (
    <>
      <PageHeader
        title={t('recurring.title')}
        subtitle={t('recurring.subtitle')}
        actions={
          <Button variant="primary" onClick={() => { setEditing(null); setFormOpen(true) }}>
            <Plus className="size-4" /> {t('recurring.new')}
          </Button>
        }
      />
      <ErrorAlert message={error} />

      <Card className="mt-2">
        {rules.isPending ? <Spinner /> : list.length === 0 ? (
          <EmptyState title={t('recurring.emptyTitle')}>{t('recurring.emptyHelp')}</EmptyState>
        ) : (
          <div className="-mx-4 overflow-x-auto sm:mx-0">
            <table className="w-full min-w-[44rem] text-sm">
              <thead>
                <tr className="border-b border-line text-left text-xs text-muted">
                  <th className="px-4 py-2 font-medium sm:px-2">{t('recurring.colDescription')}</th>
                  <th className="px-2 py-2 font-medium">{t('recurring.colSchedule')}</th>
                  <th className="px-2 py-2 font-medium">{t('recurring.next')}</th>
                  <th className="px-2 py-2 text-right font-medium">{t('common.amount')}</th>
                  <th className="w-28 px-2 py-2"><span className="sr-only">{t('entries.actions')}</span></th>
                </tr>
              </thead>
              <tbody>
                {list.map((r) => {
                  const cat = byId.get(r.categoryId)
                  return (
                    <tr key={r.id} className={`border-b border-line last:border-0 hover:bg-surface-2 ${r.active ? '' : 'opacity-60'}`}>
                      <td className="px-4 py-2 sm:px-2">
                        <p className="font-medium">{r.description || (cat?.name ?? '—')}</p>
                        <p className="flex items-center gap-1.5 text-xs text-muted">
                          <span className="size-2 rounded-full" style={{ background: cat?.color }} aria-hidden />
                          {cat?.name ?? '—'}
                        </p>
                      </td>
                      <td className="px-2 py-2 text-ink-2">
                        {t(`recurring.freq.${r.frequency}` as MessageKey)}
                        {r.endDate && <p className="text-xs text-muted">{t('recurring.until', { date: date(r.endDate) })}</p>}
                      </td>
                      <td className="tabular px-2 py-2">
                        {!r.active ? <Badge>{t('recurring.paused')}</Badge>
                          : r.nextDate ? date(r.nextDate) : <Badge>{t('recurring.ended')}</Badge>}
                      </td>
                      <td className={`tabular px-2 py-2 text-right font-medium ${r.kind === 'INCOME' ? 'text-good' : 'text-ink'}`}>
                        {r.kind === 'INCOME' ? '+' : '−'} {money(r.amount, r.currency)}
                      </td>
                      <td className="px-2 py-2">
                        <div className="flex justify-end gap-1">
                          <button type="button" className="rounded p-1.5 text-muted hover:bg-surface hover:text-ink"
                            aria-label={r.active ? t('recurring.pause') : t('recurring.resume')}
                            title={r.active ? t('recurring.pause') : t('recurring.resumeHint')} onClick={() => toggle(r)}>
                            {r.active ? <Pause className="size-4" /> : <Play className="size-4" />}
                          </button>
                          <button type="button" className="rounded p-1.5 text-muted hover:bg-surface hover:text-ink" aria-label={t('common.edit')}
                            onClick={() => { setEditing(r); setFormOpen(true) }}>
                            <Pencil className="size-4" />
                          </button>
                          <button type="button" className="rounded p-1.5 text-muted hover:bg-surface hover:text-bad" aria-label={t('common.delete')}
                            onClick={() => onDelete(r)}>
                            <Trash2 className="size-4" />
                          </button>
                        </div>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Modal title={editing ? t('recurring.edit') : t('recurring.new')} open={formOpen} onClose={() => setFormOpen(false)}>
        {formOpen && <RecurringForm rule={editing} onDone={() => setFormOpen(false)} />}
      </Modal>
    </>
  )
}

function RecurringForm({ rule, onDone }: { rule: RecurringEntry | null; onDone: () => void }) {
  const { t } = useI18n()
  const me = useMe().data
  const categories = useCategories().data ?? []
  const save = useSaveRecurring()

  const [kind, setKind] = useState<EntryKind>(rule?.kind ?? 'EXPENSE')
  const [categoryId, setCategoryId] = useState<number | ''>(rule?.categoryId ?? '')
  const [amount, setAmount] = useState(rule ? String(rule.amount) : '')
  const [currency, setCurrency] = useState(rule?.currency ?? me?.baseCurrency ?? 'CHF')
  const [description, setDescription] = useState(rule?.description ?? '')
  const [frequency, setFrequency] = useState<Frequency>(rule?.frequency ?? 'MONTHLY')
  const [startDate, setStartDate] = useState(rule?.startDate ?? today())
  const [endDate, setEndDate] = useState(rule?.endDate ?? '')
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  const options = categories.filter((c) => c.kind === kind)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    const parsed = parseDecimal(amount)
    if (parsed === null || parsed <= 0) {
      setFieldErrors({ amount: t('entryForm.amountRequired') })
      return
    }
    if (categoryId === '') {
      setFieldErrors({ categoryId: t('entryForm.categoryRequired') })
      return
    }
    setFieldErrors({})
    try {
      await save.mutateAsync({
        id: rule?.id,
        kind,
        categoryId,
        amount: parsed,
        currency: currency.toUpperCase(),
        description: description.trim() || null,
        frequency,
        startDate,
        endDate: endDate || null,
        active: rule?.active ?? true,
      })
      onDone()
    } catch (err) {
      setError(errorMessage(err))
      if (err instanceof ApiError && err.fieldErrors) setFieldErrors(err.fieldErrors)
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4">
      {rule && <p className="rounded-lg bg-surface-2 px-3 py-2 text-xs text-ink-2">{t('recurring.editNote')}</p>}
      <Segmented
        label={t('entryForm.kind')}
        value={kind}
        onChange={(k) => { setKind(k); setCategoryId('') }}
        options={[
          { value: 'EXPENSE', label: t('entryForm.expense') },
          { value: 'INCOME', label: t('entryForm.income') },
        ]}
      />
      <Field label={t('entryForm.descriptionOptional')} error={fieldErrors.description}>
        {(id) => <input id={id} className="input" maxLength={500} value={description} onChange={(e) => setDescription(e.target.value)} />}
      </Field>
      <div className="grid grid-cols-2 gap-3">
        <Field label={t('entries.category')} error={fieldErrors.categoryId}>
          {(id) => (
            <select id={id} className="input" required value={categoryId}
              onChange={(e) => setCategoryId(e.target.value ? Number(e.target.value) : '')}>
              <option value="">{t('entryForm.choose')}</option>
              {options.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          )}
        </Field>
        <Field label={t('recurring.frequency')} error={fieldErrors.frequency}>
          {(id) => (
            <select id={id} className="input" value={frequency} onChange={(e) => setFrequency(e.target.value as Frequency)}>
              {FREQUENCIES.map((f) => <option key={f} value={f}>{t(`recurring.freq.${f}` as MessageKey)}</option>)}
            </select>
          )}
        </Field>
        <Field label={t('common.amount')} error={fieldErrors.amount}>
          {(id) => (
            <input id={id} className="input tabular" inputMode="decimal" required placeholder="0.00"
              value={amount} onChange={(e) => setAmount(e.target.value)} />
          )}
        </Field>
        <Field label={t('common.currency')} error={fieldErrors.currency}>
          {(id) => (
            <>
              <input id={id} className="input uppercase" list="recurring-currencies" maxLength={3} required value={currency}
                onChange={(e) => setCurrency(e.target.value.toUpperCase())} />
              <datalist id="recurring-currencies">{COMMON_CURRENCIES.map((c) => <option key={c} value={c} />)}</datalist>
            </>
          )}
        </Field>
        <Field label={t('recurring.startDate')} error={fieldErrors.startDate} hint={rule ? undefined : t('recurring.startHint')}>
          {(id) => <input id={id} type="date" className="input" required value={startDate} onChange={(e) => setStartDate(e.target.value)} />}
        </Field>
        <Field label={t('recurring.endDate')} error={fieldErrors.endDate} hint={t('recurring.endHint')}>
          {(id) => <input id={id} type="date" className="input" min={startDate} value={endDate} onChange={(e) => setEndDate(e.target.value)} />}
        </Field>
      </div>
      <ErrorAlert message={error} />
      <div className="flex justify-end">
        <Button type="submit" variant="primary" loading={save.isPending}>{t('common.save')}</Button>
      </div>
    </form>
  )
}
