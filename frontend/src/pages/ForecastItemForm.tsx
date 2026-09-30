import { useState, type FormEvent } from 'react'
import type { Category, CategoryKind, ForecastItem, ForecastSchedule } from '../api/types'
import { Button, Field, Segmented } from '../components/ui'
import { useI18n } from '../i18n'
import { monthName, parseDecimal } from '../lib/format'
import { CategoryOptions } from '../components/CategoryOptions'

const MONTHS = Array.from({ length: 12 }, (_, i) => i + 1)

/** An extra item of a forecast: kept in the scenario being edited, saved with it. */
export function ForecastItemForm({ item, categories, currency, onDone }: {
  item: ForecastItem | null
  categories: Category[]
  currency: string
  onDone: (item: ForecastItem) => void
}) {
  const { t } = useI18n()
  const [description, setDescription] = useState(item?.description ?? '')
  const [kind, setKind] = useState<CategoryKind>(item?.kind ?? 'EXPENSE')
  const [categoryId, setCategoryId] = useState<number | null>(item?.categoryId ?? null)
  const [amount, setAmount] = useState(item ? String(item.amount) : '')
  const [schedule, setSchedule] = useState<ForecastSchedule>(item?.schedule ?? 'MONTHLY')
  const [startMonth, setStartMonth] = useState(item?.startMonth ?? 1)
  const [endMonth, setEndMonth] = useState(item?.endMonth ?? 12)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const options = categories.filter((c) => c.kind === kind)

  function changeKind(next: CategoryKind) {
    setKind(next)
    // A category of the other kind would be refused
    if (categoryId !== null && !categories.some((c) => c.id === categoryId && c.kind === next)) setCategoryId(null)
  }

  function submit(e: FormEvent) {
    e.preventDefault()
    const parsed = parseDecimal(amount)
    const found: Record<string, string> = {}
    if (!description.trim()) found.description = t('forecast.descriptionRequired')
    if (parsed === null || parsed === 0) found.amount = t('forecast.amountRequired')
    if (schedule === 'MONTHLY' && endMonth < startMonth) found.endMonth = t('error.invalid_months')
    setErrors(found)
    if (Object.keys(found).length > 0) return
    onDone({
      description: description.trim(),
      kind,
      categoryId,
      amount: parsed!,
      schedule,
      startMonth,
      endMonth: schedule === 'MONTHLY' && endMonth !== 12 ? endMonth : null,
    })
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4">
      <Field label={t('forecast.itemDescription')} hint={t('forecast.itemDescriptionHint')} error={errors.description}>
        {(id) => <input id={id} className="input" maxLength={100} required autoFocus value={description} onChange={(e) => setDescription(e.target.value)} />}
      </Field>
      <div className="flex flex-col gap-1">
        <span className="text-xs font-medium text-ink-2">{t('cashflow.matrixKind')}</span>
        <Segmented label={t('cashflow.matrixKind')} value={kind} onChange={changeKind}
          options={[{ value: 'EXPENSE', label: t('entryForm.expense') }, { value: 'INCOME', label: t('entryForm.income') }]} />
      </div>
      <div className="grid grid-cols-2 gap-3">
        <Field label={t('cashflow.colCategory')} hint={t('forecast.categoryHint')}>
          {(id) => (
            <select id={id} className="input" value={categoryId ?? ''} onChange={(e) => setCategoryId(e.target.value ? Number(e.target.value) : null)}>
              <option value="">{t('forecast.noCategory')}</option>
              <CategoryOptions categories={options} />
            </select>
          )}
        </Field>
        <Field label={t('forecast.itemAmount', { currency })} hint={t('forecast.itemAmountHint')} error={errors.amount}>
          {(id) => <input id={id} className="input tabular" inputMode="decimal" required placeholder="0.00" value={amount} onChange={(e) => setAmount(e.target.value)} />}
        </Field>
      </div>
      <div className="flex flex-col gap-1">
        <span className="text-xs font-medium text-ink-2">{t('forecast.schedule')}</span>
        <Segmented label={t('forecast.schedule')} value={schedule} onChange={setSchedule}
          options={[{ value: 'MONTHLY', label: t('forecast.scheduleMONTHLY') }, { value: 'ONCE', label: t('forecast.scheduleONCE') }]} />
      </div>
      <div className="grid grid-cols-2 gap-3">
        <Field label={schedule === 'MONTHLY' ? t('forecast.startMonth') : t('forecast.month')}>
          {(id) => (
            <select id={id} className="input capitalize" value={startMonth} onChange={(e) => setStartMonth(Number(e.target.value))}>
              {MONTHS.map((m) => <option key={m} value={m}>{monthName(m - 1)}</option>)}
            </select>
          )}
        </Field>
        {schedule === 'MONTHLY' && (
          <Field label={t('forecast.endMonth')} error={errors.endMonth}>
            {(id) => (
              <select id={id} className="input capitalize" value={endMonth} onChange={(e) => setEndMonth(Number(e.target.value))}>
                {MONTHS.map((m) => <option key={m} value={m}>{monthName(m - 1)}</option>)}
              </select>
            )}
          </Field>
        )}
      </div>
      <div className="flex justify-end">
        <Button type="submit" variant="primary">{t('common.save')}</Button>
      </div>
    </form>
  )
}
