import { useState, type FormEvent } from 'react'
import { ApiError, errorMessage } from '../api/client'
import { useMe, useSavePosition } from '../api/hooks'
import type { AssetClass, Position } from '../api/types'
import { Button, ErrorAlert, Field, Modal } from '../components/ui'
import { useI18n } from '../i18n'
import { ASSET_CLASSES, assetClassLabel, COMMON_CURRENCIES, formatIban } from '../lib/format'

export function PositionFormModal({ position, open, onClose, onSaved }: {
  position: Position | null
  open: boolean
  onClose: () => void
  onSaved?: (p: Position) => void
}) {
  const { t } = useI18n()
  return (
    <Modal title={position ? t('positions.edit') : t('positions.new')} open={open} onClose={onClose}>
      {open && <PositionForm position={position} onDone={(p) => { onSaved?.(p); onClose() }} />}
    </Modal>
  )
}

function PositionForm({ position, onDone }: { position: Position | null; onDone: (p: Position) => void }) {
  const me = useMe().data
  const save = useSavePosition()
  const { t } = useI18n()
  const [name, setName] = useState(position?.name ?? '')
  const [symbol, setSymbol] = useState(position?.symbol ?? '')
  const [iban, setIban] = useState(position?.iban ? formatIban(position.iban) : '')
  const [assetClass, setAssetClass] = useState<AssetClass>(position?.assetClass ?? 'CASH')
  const [currency, setCurrency] = useState(position?.currency ?? me?.baseCurrency ?? 'CHF')
  const [notes, setNotes] = useState(position?.notes ?? '')
  const [archived, setArchived] = useState(position?.archived ?? false)
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  // Bank and pension accounts have one; a bank statement names the account by it
  const hasIban = assetClass === 'CASH' || assetClass === 'PENSION'

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setFieldErrors({})
    try {
      const saved = await save.mutateAsync({
        id: position?.id, name, symbol, assetClass, currency, notes, archived, iban: hasIban ? iban : '',
      })
      onDone(saved)
    } catch (err) {
      setError(errorMessage(err))
      if (err instanceof ApiError && err.fieldErrors) setFieldErrors(err.fieldErrors)
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4">
      <Field label={t('common.name')} error={fieldErrors.name} hint={t('positionForm.nameHint')}>
        {(id) => <input id={id} className="input" required maxLength={100} autoFocus value={name} onChange={(e) => setName(e.target.value)} />}
      </Field>
      <div className="grid grid-cols-2 gap-3">
        <Field label={t('positionForm.assetClass')} error={fieldErrors.assetClass}>
          {(id) => (
            <select id={id} className="input" value={assetClass} onChange={(e) => setAssetClass(e.target.value as AssetClass)}>
              {ASSET_CLASSES.map((c) => <option key={c} value={c}>{assetClassLabel(c)}</option>)}
            </select>
          )}
        </Field>
        <Field label={t('positionForm.priceCurrency')} error={fieldErrors.currency}>
          {(id) => (
            <>
              <input id={id} className="input uppercase" list="position-currencies" maxLength={3} required value={currency}
                onChange={(e) => setCurrency(e.target.value.toUpperCase())} />
              <datalist id="position-currencies">{COMMON_CURRENCIES.map((c) => <option key={c} value={c}>{c}</option>)}</datalist>
            </>
          )}
        </Field>
      </div>
      {assetClass !== 'CASH' && (
        <Field label={t('positionForm.symbol')} error={fieldErrors.symbol}>
          {(id) => <input id={id} className="input uppercase" maxLength={32} value={symbol} onChange={(e) => setSymbol(e.target.value)} />}
        </Field>
      )}
      {hasIban && (
        <Field label={t('positionForm.iban')} error={fieldErrors.iban} hint={t('positionForm.ibanHint')}>
          {(id) => <input id={id} className="input uppercase" maxLength={50} autoComplete="off" spellCheck={false} value={iban}
            placeholder="CH93 0076 2011 6238 5295 7" onChange={(e) => setIban(e.target.value)} />}
        </Field>
      )}
      <Field label={t('positionForm.notes')} error={fieldErrors.notes}>
        {(id) => <textarea id={id} className="input" rows={2} maxLength={1000} value={notes} onChange={(e) => setNotes(e.target.value)} />}
      </Field>
      {position && (
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={archived} onChange={(e) => setArchived(e.target.checked)} />
          {t('positionForm.archived')}
        </label>
      )}
      <ErrorAlert message={error} />
      <div className="flex justify-end">
        <Button type="submit" variant="primary" loading={save.isPending}>{t('common.save')}</Button>
      </div>
    </form>
  )
}
