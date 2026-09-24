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
import { ASSET_CLASS_LABEL, compact, date, money, number, parseDecimal, today } from '../lib/format'
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

  if (position.isPending) return <Spinner />
  if (!position.data) {
    return <EmptyState title="Posizione non trovata"><Link to="/posizioni" className="text-accent underline">Torna alle posizioni</Link></EmptyState>
  }
  const p = position.data
  const isCash = p.assetClass === 'CASH'

  async function onDeletePosition() {
    if (!confirm(`Eliminare “${p.name}” e tutte le sue rilevazioni? L'operazione non si può annullare.`)) return
    try {
      await removePosition.mutateAsync(p.id)
      navigate('/posizioni', { replace: true })
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  async function onDeleteSnapshot(s: Snapshot) {
    if (!confirm(`Eliminare la rilevazione del ${date(s.date)}?`)) return
    try {
      await removeSnapshot.mutateAsync(s.id)
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  return (
    <>
      <Link to="/posizioni" className="mb-3 inline-flex items-center gap-1 text-sm text-ink-2 hover:text-ink">
        <ArrowLeft className="size-4" /> Posizioni
      </Link>
      <PageHeader
        title={p.name}
        subtitle={
          <span className="flex flex-wrap items-center gap-2">
            {ASSET_CLASS_LABEL[p.assetClass]} · prezzi in {p.currency}
            {p.symbol && <Badge>{p.symbol}</Badge>}
            {p.archived && <Badge>archiviata</Badge>}
          </span>
        }
        actions={
          <>
            <Button onClick={() => setEditOpen(true)}><Pencil className="size-4" /> Modifica</Button>
            <Button variant="danger" onClick={onDeletePosition}><Trash2 className="size-4" /> Elimina</Button>
          </>
        }
      />
      <ErrorAlert message={error} />
      {p.notes && <p className="mb-4 whitespace-pre-line text-sm text-ink-2">{p.notes}</p>}

      <div className="mb-4 grid gap-4 lg:grid-cols-[1fr_2fr]">
        <Card title="Valore attuale">
          <p className="text-3xl font-semibold tracking-tight">{p.latest ? money(p.latest.value, p.currency) : '—'}</p>
          {p.latest && (
            <p className="mt-1 text-sm text-ink-2">
              {isCash ? 'Saldo' : `${number(p.latest.quantity)} × ${money(p.latest.unitPrice, p.currency)}`} al {date(p.latest.date)}
            </p>
          )}
        </Card>
        <Card title="Andamento del valore">
          {(snapshots.data?.length ?? 0) < 2
            ? <p className="py-6 text-center text-sm text-muted">Servono almeno due rilevazioni per il grafico.</p>
            : <ValueChart snapshots={snapshots.data!} currency={p.currency} />}
        </Card>
      </div>

      <Card title="Rilevazioni" actions={<Button variant="primary" onClick={() => setSnapshotEdit('new')}><Plus className="size-4" /> Nuova rilevazione</Button>}>
        {snapshots.isPending ? <Spinner /> : (snapshots.data?.length ?? 0) === 0 ? (
          <EmptyState title="Nessuna rilevazione">
            {isCash ? 'Registra il saldo del conto a una data.' : 'Registra quantità posseduta e prezzo unitario a una data.'}
          </EmptyState>
        ) : (
          <div className="-mx-4 overflow-x-auto sm:mx-0">
            <table className="w-full min-w-[32rem] text-sm">
              <thead>
                <tr className="border-b border-line text-left text-xs text-muted">
                  <th className="px-4 py-2 font-medium sm:px-2">Data</th>
                  {!isCash && <th className="px-2 py-2 text-right font-medium">Quantità</th>}
                  {!isCash && <th className="px-2 py-2 text-right font-medium">Prezzo</th>}
                  <th className="px-2 py-2 text-right font-medium">{isCash ? 'Saldo' : 'Valore'}</th>
                  <th className="px-2 py-2 font-medium">Nota</th>
                  <th className="w-20 px-2 py-2"><span className="sr-only">Azioni</span></th>
                </tr>
              </thead>
              <tbody>
                {snapshots.data!.map((s) => (
                  <tr key={s.id} className="border-b border-line last:border-0 hover:bg-surface-2">
                    <td className="tabular px-4 py-2 sm:px-2">{date(s.date)}</td>
                    {!isCash && <td className="tabular px-2 py-2 text-right text-ink-2">{number(s.quantity)}</td>}
                    {!isCash && <td className="tabular px-2 py-2 text-right text-ink-2">{money(s.unitPrice, p.currency, s.unitPrice < 1 ? 6 : 2)}</td>}
                    <td className="tabular px-2 py-2 text-right font-medium">{money(s.value, p.currency)}</td>
                    <td className="max-w-48 truncate px-2 py-2 text-ink-2">{s.note}</td>
                    <td className="px-2 py-2">
                      <div className="flex justify-end gap-1">
                        <button type="button" className="rounded p-1.5 text-muted hover:text-ink" aria-label="Modifica" onClick={() => setSnapshotEdit(s)}>
                          <Pencil className="size-4" />
                        </button>
                        <button type="button" className="rounded p-1.5 text-muted hover:text-bad" aria-label="Elimina" onClick={() => onDeleteSnapshot(s)}>
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
      <Modal title={snapshotEdit === 'new' ? 'Nuova rilevazione' : 'Modifica rilevazione'} open={snapshotEdit !== null} onClose={() => setSnapshotEdit(null)}>
        {snapshotEdit !== null && (
          <SnapshotForm position={p} snapshot={snapshotEdit === 'new' ? null : snapshotEdit} onDone={() => setSnapshotEdit(null)} />
        )}
      </Modal>
    </>
  )
}

function SnapshotForm({ position, snapshot, onDone }: { position: Position; snapshot: Snapshot | null; onDone: () => void }) {
  const isCash = position.assetClass === 'CASH'
  const save = useSaveSnapshot(position.id)
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
      setError('Inserisci valori numerici non negativi.')
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
      <Field label="Data" hint={!snapshot ? 'Se esiste già una rilevazione in questa data verrà sovrascritta.' : undefined}>
        {(id) => <input id={id} type="date" className="input" required value={date} onChange={(e) => setDate(e.target.value)} />}
      </Field>
      <div className={`grid gap-3 ${isCash ? '' : 'grid-cols-2'}`}>
        <Field label={isCash ? `Saldo (${position.currency})` : 'Quantità'} hint={!isCash ? 'Metti 0 se hai venduto tutto' : undefined}>
          {(id) => <input id={id} className="input tabular" inputMode="decimal" required autoFocus value={quantity} onChange={(e) => setQuantity(e.target.value)} />}
        </Field>
        {!isCash && (
          <Field label={`Prezzo unitario (${position.currency})`}>
            {(id) => <input id={id} className="input tabular" inputMode="decimal" required value={price} onChange={(e) => setPrice(e.target.value)} />}
          </Field>
        )}
      </div>
      {!isCash && <p className="text-sm text-ink-2">Valore: <span className="tabular font-medium text-ink">{money(value, position.currency)}</span></p>}
      <Field label="Nota (facoltativa)">
        {(id) => <input id={id} className="input" maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />}
      </Field>
      <ErrorAlert message={error} />
      <div className="flex justify-end">
        <Button type="submit" variant="primary" loading={save.isPending}>Salva</Button>
      </div>
    </form>
  )
}

function ValueChart({ snapshots, currency }: { snapshots: Snapshot[]; currency: string }) {
  const theme = useChartTheme()
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
            return s ? <TooltipCard title={date(s.date)} currency={currency} rows={[{ key: 'v', label: 'Valore', color, value: s.value }]} /> : null
          }} />
          <Area type="linear" dataKey="value" stroke={color} strokeWidth={2} fill={color} fillOpacity={0.1}
            dot={{ r: 3, fill: color, stroke: theme.surface, strokeWidth: 2 }} isAnimationActive={false} />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  )
}
