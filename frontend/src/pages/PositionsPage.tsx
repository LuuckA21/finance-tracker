import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { Archive, Plus, RefreshCw } from 'lucide-react'
import { usePositions } from '../api/hooks'
import type { AssetClass, Position } from '../api/types'
import { Badge, Button, Card, EmptyState, PageHeader, Spinner } from '../components/ui'
import { assetClassColor, useChartTheme } from '../charts/theme'
import { ASSET_CLASSES, ASSET_CLASS_LABEL, date, money, number } from '../lib/format'
import { PositionFormModal } from './PositionForm'

export function PositionsPage() {
  const positions = usePositions()
  const theme = useChartTheme()
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const [showArchived, setShowArchived] = useState(false)

  const list = (positions.data ?? []).filter((p) => showArchived || !p.archived)
  const groups = ASSET_CLASSES
    .map((c) => ({ c, items: list.filter((p) => p.assetClass === c) }))
    .filter((g) => g.items.length > 0)
  const archivedCount = (positions.data ?? []).filter((p) => p.archived).length

  return (
    <>
      <PageHeader
        title="Posizioni"
        subtitle="Conti, crypto, ETF, azioni e altri beni. Il valore deriva dall'ultima rilevazione di quantità e prezzo."
        actions={
          <>
            <Link to="/aggiorna"><Button><RefreshCw className="size-4" /> Aggiorna valori</Button></Link>
            <Button variant="primary" onClick={() => setOpen(true)}><Plus className="size-4" /> Nuova posizione</Button>
          </>
        }
      />

      {positions.isPending ? <Spinner /> : groups.length === 0 ? (
        <EmptyState title="Nessuna posizione">Crea una posizione per ogni conto o investimento che vuoi seguire.</EmptyState>
      ) : (
        <div className="flex flex-col gap-4">
          {groups.map(({ c, items }) => (
            <Card key={c} title={
              <span className="flex items-center gap-2">
                <span className="size-2.5 rounded-full" style={{ background: assetClassColor(theme, c as AssetClass) }} aria-hidden />
                {ASSET_CLASS_LABEL[c]}
              </span>
            }>
              <ul className="divide-y divide-line">
                {items.map((p) => <PositionRow key={p.id} p={p} />)}
              </ul>
            </Card>
          ))}
        </div>
      )}

      {archivedCount > 0 && (
        <button type="button" className="mt-4 inline-flex items-center gap-1.5 text-sm text-ink-2 hover:text-ink" onClick={() => setShowArchived((v) => !v)}>
          <Archive className="size-4" /> {showArchived ? 'Nascondi' : 'Mostra'} posizioni archiviate ({archivedCount})
        </button>
      )}

      <PositionFormModal position={null} open={open} onClose={() => setOpen(false)} onSaved={(p) => navigate(`/posizioni/${p.id}`)} />
    </>
  )
}

function PositionRow({ p }: { p: Position }) {
  return (
    <li>
      <Link to={`/posizioni/${p.id}`} className="-mx-2 flex items-center justify-between gap-3 rounded-lg px-2 py-2.5 hover:bg-surface-2">
        <div className="min-w-0">
          <p className="flex items-center gap-2 truncate font-medium">
            {p.name}
            {p.symbol && <span className="text-xs font-normal text-muted">{p.symbol}</span>}
            {p.archived && <Badge>archiviata</Badge>}
          </p>
          <p className="text-xs text-muted">
            {p.latest
              ? p.assetClass === 'CASH'
                ? `Saldo al ${date(p.latest.date)}`
                : `${number(p.latest.quantity)} × ${money(p.latest.unitPrice, p.currency)} · ${date(p.latest.date)}`
              : 'Nessuna rilevazione'}
          </p>
        </div>
        <p className="tabular shrink-0 font-medium">{p.latest ? money(p.latest.value, p.currency) : '—'}</p>
      </Link>
    </li>
  )
}
