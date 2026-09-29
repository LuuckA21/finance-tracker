import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { Pencil, Plus, Trash2 } from 'lucide-react'
import { ApiError, errorMessage } from '../api/client'
import { useDeleteGoal, useGoals, useMe, usePositions, useSaveGoal } from '../api/hooks'
import type { Goal, GoalKind, Position } from '../api/types'
import { GoalProgress } from '../components/GoalProgress'
import { Button, Card, EmptyState, ErrorAlert, Field, MissingRatesNotice, Modal, PageHeader, Segmented, Spinner } from '../components/ui'
import { useI18n } from '../i18n'
import { COMMON_CURRENCIES, parseDecimal } from '../lib/format'

/** Savings goals: a balance to reach on some positions, or a yearly amount to put into them. */
export function GoalsPage() {
  const { t } = useI18n()
  const goals = useGoals()
  const positions = usePositions().data ?? []
  const remove = useDeleteGoal()
  const base = useMe().data?.baseCurrency ?? 'CHF'
  const [editing, setEditing] = useState<Goal | 'new' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const byId = new Map(positions.map((p) => [p.id, p]))
  const missing = [...new Set((goals.data ?? []).flatMap((g) => g.unconvertedCurrencies))]

  async function onDelete(goal: Goal) {
    if (!confirm(t('goals.confirmDelete', { name: goal.name }))) return
    setError(null)
    try {
      await remove.mutateAsync(goal.id)
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  return (
    <>
      <PageHeader title={t('goals.title')} subtitle={t('goals.subtitle', { currency: base })}
        actions={<Button variant="primary" onClick={() => setEditing('new')}><Plus className="size-4" /> {t('goals.new')}</Button>} />
      <ErrorAlert message={error} />
      <MissingRatesNotice currencies={missing} baseCurrency={base} />

      {goals.isPending ? <Spinner /> : (goals.data ?? []).length === 0 ? (
        <Card><EmptyState title={t('goals.emptyTitle')}>{t('goals.emptyHelp')}</EmptyState></Card>
      ) : (
        <Card>
          <ul className="flex flex-col divide-y divide-line">
            {goals.data!.map((goal) => (
              <li key={goal.id} className="flex items-start gap-2 py-3">
                <div className="min-w-0 flex-1">
                  <GoalProgress goal={goal} />
                  <p className="mt-1 text-xs text-muted">
                    {goal.positionIds.map((id) => byId.get(id)?.name).filter(Boolean).join(' · ')}
                  </p>
                </div>
                <button type="button" className="rounded p-1.5 text-muted hover:bg-surface-2 hover:text-ink" aria-label={t('goals.editGoal', { name: goal.name })} onClick={() => setEditing(goal)}>
                  <Pencil className="size-4" />
                </button>
                <button type="button" className="rounded p-1.5 text-muted hover:bg-surface-2 hover:text-bad" aria-label={t('goals.deleteGoal', { name: goal.name })} onClick={() => onDelete(goal)}>
                  <Trash2 className="size-4" />
                </button>
              </li>
            ))}
          </ul>
        </Card>
      )}

      <Modal title={editing === 'new' ? t('goals.new') : t('goals.edit')} open={editing !== null} onClose={() => setEditing(null)}>
        {editing !== null && <GoalForm goal={editing === 'new' ? null : editing} positions={positions} base={base} onDone={() => setEditing(null)} />}
      </Modal>
    </>
  )
}

function GoalForm({ goal, positions, base, onDone }: { goal: Goal | null; positions: Position[]; base: string; onDone: () => void }) {
  const { t } = useI18n()
  const save = useSaveGoal()
  const [name, setName] = useState(goal?.name ?? '')
  const [kind, setKind] = useState<GoalKind>(goal?.kind ?? 'BALANCE')
  const [amount, setAmount] = useState(goal ? String(goal.targetAmount) : '')
  const [currency, setCurrency] = useState(goal?.currency ?? base)
  const [targetDate, setTargetDate] = useState(goal?.targetDate ?? '')
  const [selected, setSelected] = useState<number[]>(goal?.positionIds ?? [])
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  // Archived positions are offered only when already linked
  const options = positions.filter((p) => !p.archived || selected.includes(p.id))

  function toggle(id: number, on: boolean) {
    setSelected((s) => (on ? [...s, id] : s.filter((x) => x !== id)))
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    const parsed = parseDecimal(amount)
    const errors: Record<string, string> = {}
    if (!name.trim()) errors.name = t('goals.nameRequired')
    if (parsed === null || parsed <= 0) errors.targetAmount = t('entryForm.amountRequired')
    if (selected.length === 0) errors.positionIds = t('goals.positionsRequired')
    setFieldErrors(errors)
    if (Object.keys(errors).length > 0) return
    try {
      await save.mutateAsync({
        id: goal?.id,
        name: name.trim(),
        kind,
        targetAmount: parsed!,
        currency: currency.toUpperCase(),
        targetDate: kind === 'BALANCE' && targetDate ? targetDate : null,
        positionIds: selected,
      })
      onDone()
    } catch (err) {
      setError(errorMessage(err))
      if (err instanceof ApiError && err.fieldErrors) setFieldErrors(err.fieldErrors)
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4">
      <Field label={t('common.name')} hint={t('goals.nameHint')} error={fieldErrors.name}>
        {(id) => <input id={id} className="input" maxLength={100} required autoFocus value={name} onChange={(e) => setName(e.target.value)} />}
      </Field>
      <div className="flex flex-col gap-1">
        <span className="text-xs font-medium text-ink-2">{t('goals.kind')}</span>
        <Segmented label={t('goals.kind')} value={kind} onChange={setKind}
          options={[{ value: 'BALANCE', label: t('goals.kindBALANCE') }, { value: 'YEARLY', label: t('goals.kindYEARLY') }]} />
        <p className="text-xs text-muted">{kind === 'BALANCE' ? t('goals.kindBalanceHelp') : t('goals.kindYearlyHelp')}</p>
      </div>
      <div className="grid grid-cols-2 gap-3">
        <Field label={kind === 'BALANCE' ? t('goals.target') : t('goals.yearlyTarget')} error={fieldErrors.targetAmount}>
          {(id) => <input id={id} className="input tabular" inputMode="decimal" required placeholder="0.00" value={amount} onChange={(e) => setAmount(e.target.value)} />}
        </Field>
        <Field label={t('common.currency')} error={fieldErrors.currency}>
          {(id) => (
            <>
              <input id={id} className="input uppercase" list="goal-currencies" maxLength={3} required value={currency}
                onChange={(e) => setCurrency(e.target.value.toUpperCase())} />
              <datalist id="goal-currencies">{COMMON_CURRENCIES.map((c) => <option key={c} value={c}>{c}</option>)}</datalist>
            </>
          )}
        </Field>
      </div>
      {kind === 'BALANCE' && (
        <Field label={t('goals.targetDate')} error={fieldErrors.targetDate}>
          {(id) => <input id={id} type="date" className="input" value={targetDate} onChange={(e) => setTargetDate(e.target.value)} />}
        </Field>
      )}
      <fieldset className="flex flex-col gap-1.5">
        <legend className="mb-1 text-xs font-medium text-ink-2">{t('goals.positionsLabel')}</legend>
        <p className="text-xs text-muted">{kind === 'BALANCE' ? t('goals.positionsHelpBalance') : t('goals.positionsHelpYearly')}</p>
        {options.length === 0 ? (
          <p className="text-sm text-ink-2">{t('goals.noPositions')} <Link to="/posizioni" className="text-accent hover:underline">{t('nav.positions')}</Link></p>
        ) : (
          <div className="flex max-h-48 flex-col gap-1 overflow-y-auto rounded-lg border border-line p-2">
            {options.map((p) => (
              <label key={p.id} className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={selected.includes(p.id)} onChange={(e) => toggle(p.id, e.target.checked)} />
                {p.name}
                {p.archived && <span className="text-xs text-muted">({t('positions.archivedBadge')})</span>}
              </label>
            ))}
          </div>
        )}
        {fieldErrors.positionIds && <p className="text-xs text-bad" role="alert">{fieldErrors.positionIds}</p>}
      </fieldset>
      <ErrorAlert message={error} />
      <div className="flex justify-end">
        <Button type="submit" variant="primary" loading={save.isPending}>{t('common.save')}</Button>
      </div>
    </form>
  )
}
