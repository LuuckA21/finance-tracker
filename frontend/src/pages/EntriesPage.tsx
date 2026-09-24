import { useMemo, useState } from 'react'
import { ChevronLeft, ChevronRight, Pencil, Plus, Trash2 } from 'lucide-react'
import { errorMessage } from '../api/client'
import { useCategories, useDeleteEntry, useEntries, type EntryFilter } from '../api/hooks'
import type { CashEntry, EntryKind } from '../api/types'
import { Button, Card, EmptyState, ErrorAlert, PageHeader, Spinner } from '../components/ui'
import { date, money } from '../lib/format'
import { EntryFormModal } from './EntryForm'

const PAGE_SIZE = 50

export function EntriesPage() {
  const [filter, setFilter] = useState<EntryFilter>({ page: 0, size: PAGE_SIZE, kind: '', categoryId: '', q: '' })
  const [editing, setEditing] = useState<CashEntry | null>(null)
  const [formOpen, setFormOpen] = useState(false)
  const entries = useEntries(filter)
  const categories = useCategories().data ?? []
  const remove = useDeleteEntry()
  const [error, setError] = useState<string | null>(null)

  const byId = useMemo(() => new Map(categories.map((c) => [c.id, c])), [categories])
  const update = (patch: Partial<EntryFilter>) => setFilter((f) => ({ ...f, ...patch, page: 0 }))

  async function onDelete(entry: CashEntry) {
    if (!confirm('Eliminare questo movimento?')) return
    try {
      setError(null)
      await remove.mutateAsync(entry.id)
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  const page = entries.data

  return (
    <>
      <PageHeader
        title="Movimenti"
        subtitle="Tutte le entrate e le uscite registrate"
        actions={
          <Button variant="primary" onClick={() => { setEditing(null); setFormOpen(true) }}>
            <Plus className="size-4" /> Nuovo movimento
          </Button>
        }
      />

      <Card>
        <div className="mb-4 grid grid-cols-2 gap-2 md:grid-cols-5">
          <input type="date" className="input" aria-label="Dal" value={filter.from ?? ''} onChange={(e) => update({ from: e.target.value || undefined })} />
          <input type="date" className="input" aria-label="Al" value={filter.to ?? ''} onChange={(e) => update({ to: e.target.value || undefined })} />
          <select className="input" aria-label="Tipo" value={filter.kind} onChange={(e) => update({ kind: e.target.value as EntryKind | '', categoryId: '' })}>
            <option value="">Entrate e uscite</option>
            <option value="INCOME">Solo entrate</option>
            <option value="EXPENSE">Solo uscite</option>
          </select>
          <select className="input" aria-label="Categoria" value={filter.categoryId}
            onChange={(e) => update({ categoryId: e.target.value ? Number(e.target.value) : '' })}>
            <option value="">Tutte le categorie</option>
            {categories.filter((c) => !filter.kind || c.kind === filter.kind).map((c) => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </select>
          <input type="search" className="input col-span-2 md:col-span-1" placeholder="Cerca descrizione…" aria-label="Cerca"
            value={filter.q} onChange={(e) => update({ q: e.target.value })} />
        </div>

        <ErrorAlert message={error} />

        {entries.isPending ? <Spinner /> : !page || page.content.length === 0 ? (
          <EmptyState title="Nessun movimento">Aggiungi la prima entrata o uscita con “Nuovo movimento”.</EmptyState>
        ) : (
          <div className="-mx-4 overflow-x-auto sm:mx-0">
            <table className="w-full min-w-[36rem] text-sm">
              <thead>
                <tr className="border-b border-line text-left text-xs text-muted">
                  <th className="px-4 py-2 font-medium sm:px-2">Data</th>
                  <th className="px-2 py-2 font-medium">Categoria</th>
                  <th className="px-2 py-2 font-medium">Descrizione</th>
                  <th className="px-2 py-2 text-right font-medium">Importo</th>
                  <th className="w-20 px-2 py-2"><span className="sr-only">Azioni</span></th>
                </tr>
              </thead>
              <tbody>
                {page.content.map((e) => {
                  const cat = byId.get(e.categoryId)
                  return (
                    <tr key={e.id} className="border-b border-line last:border-0 hover:bg-surface-2">
                      <td className="tabular px-4 py-2 text-ink-2 sm:px-2">{date(e.date)}</td>
                      <td className="px-2 py-2">
                        <span className="flex items-center gap-2">
                          <span className="size-2.5 rounded-full" style={{ background: cat?.color }} aria-hidden />
                          {cat?.name ?? '—'}
                        </span>
                      </td>
                      <td className="max-w-64 truncate px-2 py-2 text-ink-2">{e.description}</td>
                      <td className={`tabular px-2 py-2 text-right font-medium ${e.kind === 'INCOME' ? 'text-good' : 'text-ink'}`}>
                        {e.kind === 'INCOME' ? '+' : '−'} {money(e.amount, e.currency)}
                      </td>
                      <td className="px-2 py-2">
                        <div className="flex justify-end gap-1">
                          <button type="button" className="rounded p-1.5 text-muted hover:bg-surface hover:text-ink" aria-label="Modifica"
                            onClick={() => { setEditing(e); setFormOpen(true) }}>
                            <Pencil className="size-4" />
                          </button>
                          <button type="button" className="rounded p-1.5 text-muted hover:bg-surface hover:text-bad" aria-label="Elimina"
                            onClick={() => onDelete(e)}>
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

        {page && page.totalPages > 1 && (
          <div className="mt-4 flex items-center justify-between text-sm text-ink-2">
            <span>{page.totalElements} movimenti</span>
            <div className="flex items-center gap-2">
              <Button variant="ghost" disabled={page.page === 0} onClick={() => setFilter((f) => ({ ...f, page: f.page - 1 }))} aria-label="Pagina precedente">
                <ChevronLeft className="size-4" />
              </Button>
              <span className="tabular">{page.page + 1} / {page.totalPages}</span>
              <Button variant="ghost" disabled={page.page + 1 >= page.totalPages} onClick={() => setFilter((f) => ({ ...f, page: f.page + 1 }))} aria-label="Pagina successiva">
                <ChevronRight className="size-4" />
              </Button>
            </div>
          </div>
        )}
      </Card>

      <EntryFormModal entry={editing} open={formOpen} onClose={() => setFormOpen(false)} />
    </>
  )
}
