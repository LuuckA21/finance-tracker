import { useState, type FormEvent } from 'react'
import { ApiError, errorMessage } from '../api/client'
import { useCategories, useMe, useSaveEntry } from '../api/hooks'
import type { CashEntry, EntryKind } from '../api/types'
import { Button, ErrorAlert, Field, Modal, Segmented } from '../components/ui'
import { useI18n } from '../i18n'
import { COMMON_CURRENCIES, parseDecimal, today } from '../lib/format'

let lastCurrency: string | null = null

export function EntryFormModal({ entry, open, onClose }: { entry: CashEntry | null; open: boolean; onClose: () => void }) {
  const { t } = useI18n()
  return (
    <Modal title={entry ? t('entries.edit') : t('entries.new')} open={open} onClose={onClose}>
      {open && <EntryForm entry={entry} onDone={onClose} />}
    </Modal>
  )
}

function EntryForm({ entry, onDone }: { entry: CashEntry | null; onDone: () => void }) {
  const me = useMe().data
  const categories = useCategories().data ?? []
  const save = useSaveEntry()
  const { t } = useI18n()

  const [kind, setKind] = useState<EntryKind>(entry?.kind ?? 'EXPENSE')
  const [date, setDate] = useState(entry?.date ?? today())
  const [categoryId, setCategoryId] = useState<number | ''>(entry?.categoryId ?? '')
  const [amount, setAmount] = useState(entry ? String(entry.amount) : '')
  const [currency, setCurrency] = useState(entry?.currency ?? lastCurrency ?? me?.baseCurrency ?? 'CHF')
  const [description, setDescription] = useState(entry?.description ?? '')
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  const options = categories.filter((c) => c.kind === kind)

  async function submit(e: FormEvent, again = false) {
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
        id: entry?.id,
        date,
        kind,
        categoryId,
        amount: parsed,
        currency: currency.toUpperCase(),
        description: description.trim() || null,
      })
      lastCurrency = currency.toUpperCase()
      if (again) {
        setAmount('')
        setDescription('')
      } else {
        onDone()
      }
    } catch (err) {
      setError(errorMessage(err))
      if (err instanceof ApiError && err.fieldErrors) setFieldErrors(err.fieldErrors)
    }
  }

  return (
    <form onSubmit={(e) => submit(e)} className="flex flex-col gap-4">
      <Segmented
        label={t('entryForm.kind')}
        value={kind}
        onChange={(k) => {
          setKind(k)
          setCategoryId('')
        }}
        options={[
          { value: 'EXPENSE', label: t('entryForm.expense') },
          { value: 'INCOME', label: t('entryForm.income') },
        ]}
      />
      <div className="grid grid-cols-2 gap-3">
        <Field label={t('common.date')} error={fieldErrors.date}>
          {(id) => <input id={id} type="date" className="input" required value={date} onChange={(e) => setDate(e.target.value)} />}
        </Field>
        <Field label={t('entries.category')} error={fieldErrors.categoryId}>
          {(id) => (
            <select id={id} className="input" required value={categoryId}
              onChange={(e) => setCategoryId(e.target.value ? Number(e.target.value) : '')}>
              <option value="">{t('entryForm.choose')}</option>
              {options.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          )}
        </Field>
        <Field label={t('common.amount')} error={fieldErrors.amount}>
          {(id) => (
            <input id={id} className="input tabular" inputMode="decimal" required placeholder="0.00" autoFocus
              value={amount} onChange={(e) => setAmount(e.target.value)} />
          )}
        </Field>
        <Field label={t('common.currency')} error={fieldErrors.currency}>
          {(id) => (
            <>
              <input id={id} className="input uppercase" list="currencies" maxLength={3} required value={currency}
                onChange={(e) => setCurrency(e.target.value.toUpperCase())} />
              <datalist id="currencies">{COMMON_CURRENCIES.map((c) => <option key={c} value={c} />)}</datalist>
            </>
          )}
        </Field>
      </div>
      <Field label={t('entryForm.descriptionOptional')} error={fieldErrors.description}>
        {(id) => <input id={id} className="input" maxLength={500} value={description} onChange={(e) => setDescription(e.target.value)} />}
      </Field>
      <ErrorAlert message={error} />
      <div className="flex flex-wrap justify-end gap-2">
        {!entry && (
          <Button onClick={(e) => submit(e, true)} loading={save.isPending}>{t('entryForm.saveAndAdd')}</Button>
        )}
        <Button type="submit" variant="primary" loading={save.isPending}>{t('common.save')}</Button>
      </div>
    </form>
  )
}
