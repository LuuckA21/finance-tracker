import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { ArrowLeft, Pencil, Plus, Trash2 } from 'lucide-react'
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { ApiError, errorMessage } from '../api/client'
import { useDeletePosition, useDeleteSnapshot, usePosition, useSaveSnapshot, useSnapshots } from '../api/hooks'
import type { Position, Snapshot } from '../api/types'
import { Badge, Button, Card, EmptyState, ErrorAlert, Field, Modal, PageHeader, Spinner } from '../components/ui'
import { TooltipCard } from '../charts/ChartParts'
import { useChartTheme } from '../charts/theme'
import { useI18n } from '../i18n'
import { assetClassLabel, change, compact, date, money, number, parseDecimal, signedMoney, signedPercent, today } from '../lib/format'
import { PositionFormModal } from './PositionForm'

export function PositionDetailPage() {
  const id = Number(useParams().id)
  const position = usePosition(id)
  const snapshots = useSnapshots(id)
  const removePosition = useDeletePosition()
  const removeSnapshot = useDeleteSnapshot(id)
  const navigate = useNavigate()
  const [editOpen, setEditOpen] = useState(false)
  const [snapshotEdit, setSnapshotEdit] = useState<Snapshot | null | 'new'>(null)
  const [error, setError] = useState<string | null>(null)
  const { t } = useI18n()

  if (position.isPending) return <Spinner />
  if (!position.data) {
    return <EmptyState title={t('position.notFound')}><Link to="/posizioni" className="text-accent underline">{t('position.backToList')}</Link></EmptyState>
  }
  const p = position.data
  const isCash = p.assetClass === 'CASH'
  // Newest first, as the API returns them
  const list = snapshots.data ?? []
  const oldest = list.length >= 2 ? list[list.length - 1] : null
  const overall = oldest ? change(list[0].value, oldest.value) : null

  async function onDeletePosition() {
    if (!confirm(t('position.confirmDelete', { name: p.name }))) return
    try {
      await removePosition.mutateAsync(p.id)
      navigate('/posizioni', { replace: true })
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  async function onDeleteSnapshot(s: Snapshot) {
    if (!confirm(t('position.confirmDeleteSnapshot', { date: date(s.date) }))) return
    try {
      await removeSnapshot.mutateAsync(s.id)
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  return (
    <>
      <Link to="/posizioni" className="mb-3 inline-flex items-center gap-1 text-sm text-ink-2 hover:text-ink">
        <ArrowLeft className="size-4" /> {t('positions.title')}
      </Link>
      <PageHeader
        title={p.name}
        subtitle={
          <span className="flex flex-wrap items-center gap-2">
            {assetClassLabel(p.assetClass)} · {t('position.pricesIn', { currency: p.currency })}
            {p.symbol && <Badge>{p.symbol}</Badge>}
            {p.archived && <Badge>{t('positions.archivedBadge')}</Badge>}
          </span>
        }
        actions={
          <>
            <Button onClick={() => setEditOpen(true)}><Pencil className="size-4" /> {t('common.edit')}</Button>
            <Button variant="danger" onClick={onDeletePosition}><Trash2 className="size-4" /> {t('common.delete')}</Button>
          </>
        }
      />
      <ErrorAlert message={error} />
      {p.notes && <p className="mb-4 whitespace-pre-line text-sm text-ink-2">{p.notes}</p>}

      <div className="mb-4 grid gap-4 lg:grid-cols-[1fr_2fr]">
        <Card title={t('position.currentValue')}>
          <p className="text-3xl font-semibold tracking-tight">{p.latest ? money(p.latest.value, p.currency) : '—'}</p>
          {p.latest && (
            <p className="mt-1 text-sm text-ink-2">
              {t('position.atDate', { value: isCash ? t('position.balance') : `${number(p.latest.quantity)} × ${money(p.latest.unitPrice, p.currency)}`, date: date(p.latest.date) })}
            </p>
          )}
          {overall && oldest && (
            <div className="mt-3 border-t border-line pt-3">
              <p className="text-xs text-ink-2">{t('position.sinceFirst', { date: date(oldest.date) })}</p>
              <ChangeText value={overall} currency={p.currency} className="text-base font-medium" />
            </div>
          )}
        </Card>
        <Card title={t('position.valueTrend')}>
          {(snapshots.data?.length ?? 0) < 2
            ? <p className="py-6 text-center text-sm text-muted">{t('position.chartNeedsTwo')}</p>
            : <ValueChart snapshots={snapshots.data!} currency={p.currency} />}
        </Card>
      </div>

      <Card title={t('position.snapshots')} actions={<Button variant="primary" onClick={() => setSnapshotEdit('new')}><Plus className="size-4" /> {t('position.newSnapshot')}</Button>}>
        {snapshots.isPending ? <Spinner /> : (snapshots.data?.length ?? 0) === 0 ? (
          <EmptyState title={t('position.noSnapshots')}>
            {isCash ? t('position.noSnapshotsCash') : t('position.noSnapshotsOther')}
          </EmptyState>
        ) : (
          <div className="-mx-4 overflow-x-auto sm:mx-0">
            <table className="w-full min-w-[38rem] text-sm">
              <thead>
                <tr className="border-b border-line text-left text-xs text-muted">
                  <th className="px-4 py-2 font-medium sm:px-2">{t('common.date')}</th>
                  {!isCash && <th className="px-2 py-2 text-right font-medium">{t('position.quantity')}</th>}
                  {!isCash && <th className="px-2 py-2 text-right font-medium">{t('position.price')}</th>}
                  <th className="px-2 py-2 text-right font-medium">{isCash ? t('position.balance') : t('position.value')}</th>
                  <th className="px-2 py-2 text-right font-medium" title={t('position.changeHint')}>{t('position.change')}</th>
                  <th className="px-2 py-2 font-medium">{t('common.note')}</th>
                  <th className="w-20 px-2 py-2"><span className="sr-only">{t('entries.actions')}</span></th>
                </tr>
              </thead>
              <tbody>
                {list.map((s, i) => (
                  <tr key={s.id} className="border-b border-line last:border-0 hover:bg-surface-2">
                    <td className="tabular px-4 py-2 sm:px-2">{date(s.date)}</td>
                    {!isCash && <td className="tabular px-2 py-2 text-right text-ink-2">{number(s.quantity)}</td>}
                    {!isCash && <td className="tabular px-2 py-2 text-right text-ink-2">{money(s.unitPrice, p.currency, s.unitPrice < 1 ? 6 : 2)}</td>}
                    <td className="tabular px-2 py-2 text-right font-medium">{money(s.value, p.currency)}</td>
                    <td className="px-2 py-2 text-right">
                      {i + 1 < list.length
                        ? <ChangeText value={change(s.value, list[i + 1].value)} currency={p.currency} stacked />
                        : <span className="text-muted">—</span>}
                    </td>
                    <td className="max-w-48 truncate px-2 py-2 text-ink-2">{s.note}</td>
                    <td className="px-2 py-2">
                      <div className="flex justify-end gap-1">
                        <button type="button" className="rounded p-1.5 text-muted hover:text-ink" aria-label={t('common.edit')} onClick={() => setSnapshotEdit(s)}>
                          <Pencil className="size-4" />
                        </button>
                        <button type="button" className="rounded p-1.5 text-muted hover:text-bad" aria-label={t('common.delete')} onClick={() => onDeleteSnapshot(s)}>
                          <Trash2 className="size-4" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <PositionFormModal position={p} open={editOpen} onClose={() => setEditOpen(false)} />
      <Modal title={snapshotEdit === 'new' ? t('position.newSnapshot') : t('position.editSnapshot')} open={snapshotEdit !== null} onClose={() => setSnapshotEdit(null)}>
        {snapshotEdit !== null && (
          <SnapshotForm position={p} snapshot={snapshotEdit === 'new' ? null : snapshotEdit} onDone={() => setSnapshotEdit(null)} />
        )}
      </Modal>
    </>
  )
}

/** Signed amount and percentage, coloured by direction (never by colour alone: the sign stays). */
function ChangeText({ value, currency, stacked = false, className = '' }: {
  value: { amount: number; percent: number | null }
  currency: string
  stacked?: boolean
  className?: string
}) {
  const tone = value.amount > 0 ? 'text-good' : value.amount < 0 ? 'text-bad' : 'text-muted'
  return (
    <span className={`tabular ${tone} ${stacked ? 'flex flex-col items-end leading-tight' : 'flex gap-2'} ${className}`}>
      <span>{signedMoney(value.amount, currency)}</span>
      <span className={stacked ? 'text-xs' : ''}>{signedPercent(value.percent)}</span>
    </span>
  )
}

function SnapshotForm({ position, snapshot, onDone }: { position: Position; snapshot: Snapshot | null; onDone: () => void }) {
  const isCash = position.assetClass === 'CASH'
  const save = useSaveSnapshot(position.id)
  const { t } = useI18n()
  const base = snapshot ?? position.latest
  const [date, setDate] = useState(snapshot?.date ?? today())
  const [quantity, setQuantity] = useState(base ? String(base.quantity) : '')
  const [price, setPrice] = useState(isCash ? '1' : base ? String(base.unitPrice) : '')
  const [note, setNote] = useState(snapshot?.note ?? '')
  const [error, setError] = useState<string | null>(null)

  const q = parseDecimal(quantity)
  const up = parseDecimal(price)
  const value = q !== null && up !== null ? q * up : null

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    if (q === null || q < 0 || up === null || up < 0) {
      setError(t('snapshotForm.invalidNumbers'))
      return
    }
    try {
      await save.mutateAsync({ id: snapshot?.id, date, quantity: q, unitPrice: up, note })
      onDone()
    } catch (err) {
      setError(err instanceof ApiError && err.status === 409 ? errorMessage(err) : errorMessage(err))
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4">
      <Field label={t('common.date')} hint={!snapshot ? t('snapshotForm.dateHint') : undefined}>
        {(id) => <input id={id} type="date" className="input" required value={date} onChange={(e) => setDate(e.target.value)} />}
      </Field>
      <div className={`grid gap-3 ${isCash ? '' : 'grid-cols-2'}`}>
        <Field label={isCash ? t('snapshotForm.balance', { currency: position.currency }) : t('position.quantity')} hint={!isCash ? t('snapshotForm.quantityHint') : undefined}>
          {(id) => <input id={id} className="input tabular" inputMode="decimal" required autoFocus value={quantity} onChange={(e) => setQuantity(e.target.value)} />}
        </Field>
        {!isCash && (
          <Field label={t('snapshotForm.unitPrice', { currency: position.currency })}>
            {(id) => <input id={id} className="input tabular" inputMode="decimal" required value={price} onChange={(e) => setPrice(e.target.value)} />}
          </Field>
        )}
      </div>
      {!isCash && <p className="text-sm text-ink-2">{t('snapshotForm.value')} <span className="tabular font-medium text-ink">{money(value, position.currency)}</span></p>}
      <Field label={t('snapshotForm.noteOptional')}>
        {(id) => <input id={id} className="input" maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />}
      </Field>
      <ErrorAlert message={error} />
      <div className="flex justify-end">
        <Button type="submit" variant="primary" loading={save.isPending}>{t('common.save')}</Button>
      </div>
    </form>
  )
}

function ValueChart({ snapshots, currency }: { snapshots: Snapshot[]; currency: string }) {
  const theme = useChartTheme()
  const { t } = useI18n()
  const color = theme.series[0]
  const data = [...snapshots].reverse().map((s) => ({ ...s, label: date(s.date) }))
  return (
    <div style={{ height: 200 }}>
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
          <CartesianGrid vertical={false} stroke={theme.grid} />
          <XAxis dataKey="label" tickLine={false} axisLine={{ stroke: theme.axis }} tick={{ fill: theme.muted, fontSize: 12 }} minTickGap={24} />
          <YAxis width={56} tickLine={false} axisLine={false} tick={{ fill: theme.muted, fontSize: 12 }} tickFormatter={(v: number) => compact(v)} />
          <Tooltip cursor={{ stroke: theme.axis }} content={({ active, payload }) => {
            const s = active && payload && payload.length ? (payload[0].payload as Snapshot) : null
            return s ? <TooltipCard title={date(s.date)} currency={currency} rows={[{ key: 'v', label: t('position.value'), color, value: s.value }]} /> : null
          }} />
          <Area type="linear" dataKey="value" stroke={color} strokeWidth={2} fill={color} fillOpacity={0.1}
            dot={{ r: 3, fill: color, stroke: theme.surface, strokeWidth: 2 }} isAnimationActive={false} />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  )
}
