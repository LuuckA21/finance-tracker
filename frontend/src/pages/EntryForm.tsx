import { useState, type FormEvent } from 'react'
import { Plus, Trash2 } from 'lucide-react'
import { ApiError, errorMessage } from '../api/client'
import { useCategories, useCategorySuggestion, useMe, useSaveEntry, useSaveSplit, useSplit, useTags } from '../api/hooks'
import type { CashEntry, EntryKind } from '../api/types'
import { TagInput } from '../components/TagInput'
import { TransferFields } from '../components/TransferFields'
import { Button, ErrorAlert, Field, Modal, Segmented, Spinner } from '../components/ui'
import { useI18n } from '../i18n'
import { COMMON_CURRENCIES, money, parseDecimal, today } from '../lib/format'
import { normalizeText } from '../lib/rules'
import { useDebounced } from '../lib/useDebounced'
import { CategoryOptions } from '../components/CategoryOptions'

let lastCurrency: string | null = null

/** A part of a split entry while it is edited: the amount as typed. */
interface Part {
  categoryId: number | ''
  amount: string
}

const MAX_PARTS = 20
/** Amounts have up to 4 decimals: compared in ten-thousandths, so 0.1 + 0.2 makes 0.3 */
const units = (value: number) => Math.round(value * 10_000)

export function EntryFormModal({ entry, open, onClose }: { entry: CashEntry | null; open: boolean; onClose: () => void }) {
  const { t } = useI18n()
  return (
    <Modal title={entry ? t('entries.edit') : t('entries.new')} open={open} onClose={onClose}>
      {open && <EntryForm entry={entry} onDone={onClose} />}
    </Modal>
  )
}

/** A part of a split entry opens the whole payment, once its parts are loaded. */
function EntryForm({ entry, onDone }: { entry: CashEntry | null; onDone: () => void }) {
  const split = useSplit(entry?.splitGroup ?? null)
  if (entry?.splitGroup && split.isPending) return <Spinner />
  return <EntryFormBody entry={entry} split={split.data ?? null} onDone={onDone} />
}

function EntryFormBody({ entry, split, onDone }: { entry: CashEntry | null; split: CashEntry[] | null; onDone: () => void }) {
  const me = useMe().data
  const categories = useCategories().data ?? []
  const save = useSaveEntry()
  const saveSplit = useSaveSplit()
  const group = entry?.splitGroup ?? null
  const tagNames = (useTags().data?.tags ?? []).map((tag) => tag.name)
  const { t } = useI18n()

  const [kind, setKind] = useState<EntryKind>(entry?.kind ?? 'EXPENSE')
  const [date, setDate] = useState(entry?.date ?? today())
  const [categoryId, setCategoryId] = useState<number | ''>(entry?.categoryId ?? '')
  // The total of a split entry, or the amount of an ordinary one
  const [amount, setAmount] = useState(split ? String(split.reduce((sum, p) => sum + units(p.amount), 0) / 10_000)
    : entry ? String(entry.amount) : '')
  // Null: one category; otherwise the parts the amount is shared among
  const [parts, setParts] = useState<Part[] | null>(split
    ? split.map((p) => ({ categoryId: p.categoryId ?? '', amount: String(p.amount) })) : null)
  const [currency, setCurrency] = useState(entry?.currency ?? lastCurrency ?? me?.baseCurrency ?? 'CHF')
  const [description, setDescription] = useState(entry?.description ?? '')
  const [route, setRoute] = useState({ from: entry?.fromPositionId ?? null, to: entry?.toPositionId ?? null })
  const [tags, setTags] = useState<string[]>(entry?.tags ?? [])
  const isTransfer = kind === 'TRANSFER'
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  // Until the user picks a category, the rules or the past entries may suggest one from the description
  const [categoryTouched, setCategoryTouched] = useState(entry !== null)
  const settled = useDebounced(description.trim(), 400)
  const asking = !isTransfer && categoryId === '' && !categoryTouched && normalizeText(settled).length >= 3
  const found = useCategorySuggestion(settled, kind === 'INCOME' ? 'INCOME' : 'EXPENSE', asking).data
  const suggestion = asking && found && categories.some((c) => c.id === found.categoryId && c.kind === kind) ? found : null
  const chosenCategory = categoryId !== '' ? categoryId : suggestion?.categoryId ?? ''
  const splitting = parts !== null && !isTransfer
  const total = parseDecimal(amount)
  const assigned = (parts ?? []).reduce((sum, p) => sum + units(parseDecimal(p.amount) ?? 0), 0)
  const remaining = total === null ? null : units(total) - assigned

  function startSplit() {
    setParts([{ categoryId: chosenCategory, amount }, { categoryId: '', amount: '' }])
    setFieldErrors({})
  }

  /** Back to one category: the first part's, for the whole amount. */
  function stopSplit() {
    if (parts && parts[0].categoryId !== '') {
      setCategoryId(parts[0].categoryId)
      setCategoryTouched(true)
    }
    setParts(null)
    setFieldErrors({})
  }

  function changePart(index: number, patch: Partial<Part>) {
    setParts((all) => all && all.map((p, i) => (i === index ? { ...p, ...patch } : p)))
  }


  async function submit(e: FormEvent, again = false) {
    e.preventDefault()
    setError(null)
    const parsed = parseDecimal(amount)
    if (parsed === null || parsed <= 0) {
      setFieldErrors({ amount: t('entryForm.amountRequired') })
      return
    }
    if (isTransfer && group) {
      setError(t('error.split_transfer'))
      return
    }
    if (splitting) {
      const read = parts.map((p) => ({ categoryId: p.categoryId, amount: parseDecimal(p.amount) }))
      if (read.some((p) => p.categoryId === '' || p.amount === null || p.amount <= 0)) {
        setFieldErrors({ parts: t('split.partRequired') })
        return
      }
      if (read.reduce((sum, p) => sum + units(p.amount!), 0) !== units(parsed)) {
        setFieldErrors({ parts: t('split.notBalanced') })
        return
      }
      // One part left of a new split: an ordinary entry
      if (read.length > 1 || group) {
        await submitSplit(read.map((p) => ({ categoryId: p.categoryId as number, amount: p.amount! })), again)
        return
      }
    } else if (!isTransfer && chosenCategory === '') {
      setFieldErrors({ categoryId: t('entryForm.categoryRequired') })
      return
    } else if (group) {
      // A split entry back to one category
      await submitSplit([{ categoryId: chosenCategory as number, amount: parsed }], again)
      return
    }
    if (isTransfer && route.from !== null && route.from === route.to) {
      setFieldErrors({ to: t('error.transfer_same_position') })
      return
    }
    setFieldErrors({})
    try {
      const single = splitting ? parts[0].categoryId : chosenCategory
      await save.mutateAsync({
        id: entry?.id,
        date,
        kind,
        categoryId: isTransfer || single === '' ? null : single,
        amount: parsed,
        currency: currency.toUpperCase(),
        description: description.trim() || null,
        fromPositionId: isTransfer ? route.from : null,
        toPositionId: isTransfer ? route.to : null,
        tags,
      })
      saved(again)
    } catch (err) {
      setError(errorMessage(err))
      if (err instanceof ApiError && err.fieldErrors) setFieldErrors(err.fieldErrors)
    }
  }

  /** The parts of a split entry: new, replacing this ordinary entry, or replacing the parts it had. */
  async function submitSplit(chosen: { categoryId: number; amount: number }[], again: boolean) {
    setFieldErrors({})
    try {
      await saveSplit.mutateAsync({
        group,
        replaces: group ? null : entry?.id ?? null,
        date,
        kind: kind === 'INCOME' ? 'INCOME' : 'EXPENSE',
        currency: currency.toUpperCase(),
        description: description.trim() || null,
        tags,
        parts: chosen,
      })
      saved(again)
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  function saved(again: boolean) {
    lastCurrency = currency.toUpperCase()
    if (again) {
      // Tags stay: the next entry is often part of the same trip or project
      setAmount('')
      setDescription('')
      setParts(null)
    } else {
      onDone()
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
          setCategoryTouched(false)
        }}
        options={[
          { value: 'EXPENSE', label: t('entryForm.expense') },
          { value: 'INCOME', label: t('entryForm.income') },
          { value: 'TRANSFER', label: t('entryForm.transfer') },
        ]}
      />
      {isTransfer && <p className="-mt-2 text-xs text-muted">{t('transfer.help')}</p>}
      <div className="grid grid-cols-2 gap-3">
        <Field label={t('common.date')} error={fieldErrors.date}>
          {(id) => <input id={id} type="date" className="input" required value={date} onChange={(e) => setDate(e.target.value)} />}
        </Field>
        {!isTransfer && !splitting && (
          <Field label={t('entries.category')} error={fieldErrors.categoryId}
            hint={suggestion ? (suggestion.pattern ? t('entryForm.suggestedByRule', { pattern: suggestion.pattern }) : t('entryForm.suggestedByHistory')) : undefined}>
            {(id) => (
              <select id={id} className="input" required value={chosenCategory}
                onChange={(e) => { setCategoryId(e.target.value ? Number(e.target.value) : ''); setCategoryTouched(true) }}>
                <option value="">{t('entryForm.choose')}</option>
                <CategoryOptions categories={categories} kind={kind === 'INCOME' ? 'INCOME' : 'EXPENSE'} />
              </select>
            )}
          </Field>
        )}
        <Field label={splitting ? t('split.total') : t('common.amount')} error={fieldErrors.amount}>
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
              <datalist id="currencies">{COMMON_CURRENCIES.map((c) => <option key={c} value={c}>{c}</option>)}</datalist>
            </>
          )}
        </Field>
      </div>
      {!isTransfer && !splitting && (
        <div className="-mt-2">
          <button type="button" className="text-xs font-medium text-accent hover:underline" onClick={startSplit}>{t('split.start')}</button>
        </div>
      )}
      {splitting && (
        <fieldset className="flex flex-col gap-2 rounded-lg border border-line p-3">
          <legend className="px-1 text-xs text-muted">{t('split.parts')}</legend>
          {parts.map((part, i) => (
            <div key={i} className="grid grid-cols-[minmax(0,1fr)_6.5rem_auto] items-center gap-2">
              <select className="input" aria-label={t('split.partCategory', { n: i + 1 })} value={part.categoryId}
                onChange={(e) => changePart(i, { categoryId: e.target.value ? Number(e.target.value) : '' })}>
                <option value="">{t('entryForm.choose')}</option>
                <CategoryOptions categories={categories} kind={kind === 'INCOME' ? 'INCOME' : 'EXPENSE'} />
              </select>
              <input className="input tabular" inputMode="decimal" placeholder="0.00" aria-label={t('split.partAmount', { n: i + 1 })}
                value={part.amount} onChange={(e) => changePart(i, { amount: e.target.value })} />
              <button type="button" className="rounded p-1.5 text-muted hover:text-bad disabled:opacity-40" disabled={parts.length <= 1}
                aria-label={t('split.removePart', { n: i + 1 })} onClick={() => setParts((all) => all && all.filter((_, j) => j !== i))}>
                <Trash2 className="size-4" />
              </button>
            </div>
          ))}
          <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
            <span className="flex gap-3">
              <button type="button" className="inline-flex items-center gap-1 font-medium text-accent hover:underline disabled:opacity-40"
                disabled={parts.length >= MAX_PARTS} onClick={() => setParts((all) => all && [...all, { categoryId: '', amount: '' }])}>
                <Plus className="size-3.5" aria-hidden /> {t('split.addPart')}
              </button>
              <button type="button" className="text-muted hover:text-ink hover:underline" onClick={stopSplit}>{t('split.stop')}</button>
            </span>
            {remaining !== null && (
              <span role="status" className={remaining === 0 ? 'text-good' : 'text-bad'}>
                {remaining === 0 ? t('split.balanced') : t('split.remaining', { amount: money(remaining / 10_000, currency || 'CHF') })}
              </span>
            )}
          </div>
          {fieldErrors.parts && <p className="text-xs text-bad">{fieldErrors.parts}</p>}
        </fieldset>
      )}
      {isTransfer && (
        <div className="grid grid-cols-2 gap-3">
          <TransferFields from={route.from} to={route.to}
            onChange={(next) => { setRoute(next); setFieldErrors(({ to: _to, ...rest }) => rest) }}
            errors={{ from: fieldErrors.fromPositionId, to: fieldErrors.to ?? fieldErrors.toPositionId }} />
        </div>
      )}
      <Field label={t('entryForm.descriptionOptional')} error={fieldErrors.description}>
        {(id) => <input id={id} className="input" maxLength={500} value={description} onChange={(e) => setDescription(e.target.value)} />}
      </Field>
      <Field label={t('tags.label')} hint={t('tags.hint')} error={fieldErrors.tags}>
        {(id) => <TagInput id={id} value={tags} onChange={setTags} suggestions={tagNames} />}
      </Field>
      <ErrorAlert message={error} />
      <div className="flex flex-wrap justify-end gap-2">
        {!entry && (
          <Button onClick={(e) => submit(e, true)} loading={save.isPending || saveSplit.isPending}>{t('entryForm.saveAndAdd')}</Button>
        )}
        <Button type="submit" variant="primary" loading={save.isPending || saveSplit.isPending}>{t('common.save')}</Button>
      </div>
    </form>
  )
}
