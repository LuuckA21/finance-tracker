import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { errorMessage } from '../api/client'
import { useBulkSnapshot, usePositions } from '../api/hooks'
import type { Position } from '../api/types'
import { Button, Card, EmptyState, ErrorAlert, PageHeader, Spinner } from '../components/ui'
import { ASSET_CLASS_LABEL, date, money, parseDecimal, today } from '../lib/format'

interface Row {
  quantity: string
  price: string
  include: boolean
}

/** The "monthly update": one date, new quantity/price for every active position, saved atomically. */
export function BulkUpdatePage() {
  const positions = usePositions()
  const save = useBulkSnapshot()
  const navigate = useNavigate()
  const [day, setDay] = useState(today())
  const [rows, setRows] = useState<Record<number, Row>>({})
  const [error, setError] = useState<string | null>(null)

  const active = useMemo(() => (positions.data ?? []).filter((p) => !p.archived), [positions.data])

  useEffect(() => {
    const initial: Record<number, Row> = {}
    active.forEach((p) => {
      initial[p.id] = {
        quantity: p.latest ? String(p.latest.quantity) : '',
        price: p.assetClass === 'CASH' ? '1' : p.latest ? String(p.latest.unitPrice) : '',
        include: true,
      }
    })
    setRows(initial)
  }, [active])

  const set = (id: number, patch: Partial<Row>) => setRows((r) => ({ ...r, [id]: { ...r[id], ...patch } }))

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    const items: { positionId: number; quantity: number; unitPrice: number }[] = []
    for (const p of active) {
      const row = rows[p.id]
      if (!row?.include) continue
      const q = parseDecimal(row.quantity)
      const up = parseDecimal(row.price)
      if (q === null || up === null || q < 0 || up < 0) {
        setError(`Valori non validi per “${p.name}”.`)
        return
      }
      items.push({ positionId: p.id, quantity: q, unitPrice: up })
    }
    if (items.length === 0) {
      setError('Seleziona almeno una posizione.')
      return
    }
    try {
      await save.mutateAsync({ date: day, items })
      navigate('/patrimonio')
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  return (
    <>
      <PageHeader title="Aggiorna valori" subtitle="Registra in un colpo solo saldi, quantità e prezzi di tutte le posizioni a una data." />
      {positions.isPending ? <Spinner /> : active.length === 0 ? (
        <EmptyState title="Nessuna posizione attiva">Crea prima le posizioni nella pagina Posizioni.</EmptyState>
      ) : (
        <form onSubmit={submit}>
          <Card>
            <label className="mb-4 flex max-w-xs flex-col gap-1 text-xs font-medium text-ink-2">
              Data della rilevazione
              <input type="date" className="input" required value={day} max={today()} onChange={(e) => setDay(e.target.value)} />
            </label>
            <div className="-mx-4 overflow-x-auto sm:mx-0">
              <table className="w-full min-w-[40rem] text-sm">
                <thead>
                  <tr className="border-b border-line text-left text-xs text-muted">
                    <th className="w-8 px-4 py-2 sm:px-2"><span className="sr-only">Includi</span></th>
                    <th className="px-2 py-2 font-medium">Posizione</th>
                    <th className="px-2 py-2 font-medium">Quantità / saldo</th>
                    <th className="px-2 py-2 font-medium">Prezzo unitario</th>
                    <th className="px-2 py-2 text-right font-medium">Valore</th>
                  </tr>
                </thead>
                <tbody>
                  {active.map((p) => <BulkRow key={p.id} p={p} row={rows[p.id]} onChange={(patch) => set(p.id, patch)} />)}
                </tbody>
              </table>
            </div>
            <div className="mt-4 flex flex-wrap items-center justify-end gap-3">
              <ErrorAlert message={error} />
              <Button type="submit" variant="primary" loading={save.isPending}>Salva rilevazioni del {date(day)}</Button>
            </div>
          </Card>
        </form>
      )}
    </>
  )
}

function BulkRow({ p, row, onChange }: { p: Position; row: Row | undefined; onChange: (patch: Partial<Row>) => void }) {
  if (!row) return null
  const isCash = p.assetClass === 'CASH'
  const q = parseDecimal(row.quantity)
  const up = parseDecimal(row.price)
  const value = q !== null && up !== null ? q * up : null
  return (
    <tr className={`border-b border-line last:border-0 ${row.include ? '' : 'opacity-50'}`}>
      <td className="px-4 py-2 sm:px-2">
        <input type="checkbox" aria-label={`Includi ${p.name}`} checked={row.include} onChange={(e) => onChange({ include: e.target.checked })} />
      </td>
      <td className="px-2 py-2">
        <p className="font-medium">{p.name}</p>
        <p className="text-xs text-muted">
          {ASSET_CLASS_LABEL[p.assetClass]} · {p.latest ? `ultimo ${date(p.latest.date)}` : 'prima rilevazione'}
        </p>
      </td>
      <td className="px-2 py-2">
        <input className="input tabular" inputMode="decimal" aria-label={`Quantità ${p.name}`} disabled={!row.include}
          value={row.quantity} onChange={(e) => onChange({ quantity: e.target.value })} />
      </td>
      <td className="px-2 py-2">
        {isCash ? <span className="text-xs text-muted">—</span> : (
          <input className="input tabular" inputMode="decimal" aria-label={`Prezzo ${p.name}`} disabled={!row.include}
            value={row.price} onChange={(e) => onChange({ price: e.target.value })} />
        )}
      </td>
      <td className="tabular px-2 py-2 text-right font-medium">{money(value, p.currency)}</td>
    </tr>
  )
}
