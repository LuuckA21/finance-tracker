import { useMemo, useState } from 'react'
import { ChevronLeft, ChevronRight, Download, FileUp, Pencil, Plus, Repeat, Trash2 } from 'lucide-react'
import { download, errorMessage } from '../api/client'
import { entryFilterParams, useCategories, useDeleteEntry, useEntries, usePositions, useTags, type EntryFilter } from '../api/hooks'
import type { CashEntry, Category, EntryKind } from '../api/types'
import { TagCategories } from '../components/TagCategories'
import { TagChip } from '../components/TagInput'
import { amountStyle, EntryTarget } from '../components/TransferFields'
import { Button, Card, EmptyState, ErrorAlert, PageHeader, Spinner } from '../components/ui'
import { useI18n } from '../i18n'
import { date, money } from '../lib/format'
import { EntryFormModal } from './EntryForm'
import { ImportModal } from './EntryImport'
import { CategoryOptions } from '../components/CategoryOptions'

/** Stable while categories load, so memoized lookups are not rebuilt on every render. */
const NO_CATEGORIES: Category[] = []

const PAGE_SIZE = 50

export function EntriesPage() {
  const [filter, setFilter] = useState<EntryFilter>({ page: 0, size: PAGE_SIZE, kind: '', categoryId: '', q: '', tagId: '' })
  const [editing, setEditing] = useState<CashEntry | null>(null)
  const [formOpen, setFormOpen] = useState(false)
  const entries = useEntries(filter)
  const categories = useCategories().data ?? NO_CATEGORIES
  const positions = usePositions().data ?? []
  const tags = useTags().data
  const remove = useDeleteEntry()
  const [error, setError] = useState<string | null>(null)
  const [importOpen, setImportOpen] = useState(false)
  const [exporting, setExporting] = useState(false)
  const { t } = useI18n()

  const byId = useMemo(() => new Map(categories.map((c) => [c.id, c])), [categories])
  const update = (patch: Partial<EntryFilter>) => setFilter((f) => ({ ...f, ...patch, page: 0 }))

  async function onDelete(entry: CashEntry) {
    if (!confirm(t('entries.confirmDelete'))) return
    try {
      setError(null)
      await remove.mutateAsync(entry.id)
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  async function onExport() {
    setError(null)
    setExporting(true)
    try {
      const { page: _page, size: _size, ...filters } = filter
      await download(`/api/cash-entries/export?${entryFilterParams(filters)}`, 'movimenti.csv')
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setExporting(false)
    }
  }

  const page = entries.data
  const selectedTag = tags?.tags.find((tag) => tag.id === filter.tagId)
  const tagId = (name: string) => tags?.tags.find((tag) => tag.name === name)?.id

  return (
    <>
      <PageHeader
        title={t('entries.title')}
        subtitle={t('entries.subtitle')}
        actions={
          <>
            <Button onClick={onExport} loading={exporting} title={t('entries.exportHint')}>
              <Download className="size-4" /> {t('entries.export')}
            </Button>
            <Button onClick={() => setImportOpen(true)}>
              <FileUp className="size-4" /> {t('entries.import')}
            </Button>
            <Button variant="primary" onClick={() => { setEditing(null); setFormOpen(true) }}>
              <Plus className="size-4" /> {t('entries.new')}
            </Button>
          </>
        }
      />

      <Card>
        <div className={`mb-4 grid grid-cols-2 gap-2 ${tags && tags.tags.length > 0 ? 'md:grid-cols-6' : 'md:grid-cols-5'}`}>
          <input type="date" className="input" aria-label={t('entries.from')} value={filter.from ?? ''} onChange={(e) => update({ from: e.target.value || undefined })} />
          <input type="date" className="input" aria-label={t('entries.to')} value={filter.to ?? ''} onChange={(e) => update({ to: e.target.value || undefined })} />
          <select className="input" aria-label={t('entries.kind')} value={filter.kind} onChange={(e) => update({ kind: e.target.value as EntryKind | '', categoryId: '' })}>
            <option value="">{t('entries.kindAll')}</option>
            <option value="INCOME">{t('entries.kindIncome')}</option>
            <option value="EXPENSE">{t('entries.kindExpense')}</option>
            <option value="TRANSFER">{t('entries.kindTransfer')}</option>
          </select>
          <select className="input" aria-label={t('entries.category')} value={filter.categoryId} disabled={filter.kind === 'TRANSFER'}
            onChange={(e) => update({ categoryId: e.target.value ? Number(e.target.value) : '' })}>
            <option value="">{t('entries.allCategories')}</option>
            <CategoryOptions categories={categories} kind={filter.kind === 'INCOME' || filter.kind === 'EXPENSE' ? filter.kind : undefined} />
          </select>
          {tags && tags.tags.length > 0 && (
            <select className="input" aria-label={t('tags.label')} value={filter.tagId}
              onChange={(e) => update({ tagId: e.target.value ? Number(e.target.value) : '' })}>
              <option value="">{t('tags.all')}</option>
              {tags.tags.map((tag) => <option key={tag.id} value={tag.id}>{tag.name}</option>)}
            </select>
          )}
          <input type="search" className={`input ${tags && tags.tags.length > 0 ? '' : 'col-span-2'} md:col-span-1`} placeholder={t('entries.searchPlaceholder')} aria-label={t('entries.search')}
            value={filter.q} onChange={(e) => update({ q: e.target.value })} />
        </div>

        {selectedTag && tags && (
          <div className="mb-4 flex flex-col gap-1.5 rounded-lg bg-surface-2 px-3 py-2 text-sm" role="status">
            <p className="flex flex-wrap gap-x-4 gap-y-1">
            <strong>{selectedTag.name}</strong>
            <span>{t('tags.entries', { count: selectedTag.entryCount })}</span>
            {selectedTag.expense > 0 && <span>{t('tags.expense', { amount: money(selectedTag.expense, tags.baseCurrency) })}</span>}
            {selectedTag.income > 0 && <span>{t('tags.income', { amount: money(selectedTag.income, tags.baseCurrency) })}</span>}
            {selectedTag.transferred > 0 && <span>{t('tags.transferred', { amount: money(selectedTag.transferred, tags.baseCurrency) })}</span>}
            {selectedTag.firstDate && (
              <span className="text-muted">{selectedTag.firstDate === selectedTag.lastDate ? date(selectedTag.firstDate) : `${date(selectedTag.firstDate)} – ${date(selectedTag.lastDate)}`}</span>
            )}
            </p>
            <TagCategories rows={selectedTag.categories} categories={byId} currency={tags.baseCurrency} />
          </div>
        )}

        <ErrorAlert message={error} />

        {entries.isPending ? <Spinner /> : !page || page.content.length === 0 ? (
          <EmptyState title={t('entries.emptyTitle')}>{t('entries.emptyHelp')}</EmptyState>
        ) : (
          <div className="relative -mx-4 overflow-x-auto sm:mx-0">
            <table className="w-full min-w-[36rem] text-sm">
              <thead>
                <tr className="border-b border-line text-left text-xs text-muted">
                  <th className="px-4 py-2 font-medium sm:px-2">{t('common.date')}</th>
                  <th className="px-2 py-2 font-medium">{t('entries.category')}</th>
                  <th className="px-2 py-2 font-medium">{t('entries.description')}</th>
                  <th className="px-2 py-2 text-right font-medium">{t('common.amount')}</th>
                  <th className="w-20 px-2 py-2"><span className="sr-only">{t('entries.actions')}</span></th>
                </tr>
              </thead>
              <tbody>
                {page.content.map((e) => {
                  const style = amountStyle(e.kind)
                  return (
                    <tr key={e.id} className="border-b border-line last:border-0 hover:bg-surface-2">
                      <td className="tabular px-4 py-2 text-ink-2 sm:px-2">{date(e.date)}</td>
                      <td className="px-2 py-2">
                        <EntryTarget kind={e.kind} categoryId={e.categoryId} from={e.fromPositionId} to={e.toPositionId}
                          categories={byId} positions={positions} />
                      </td>
                      <td className="max-w-64 px-2 py-2 text-ink-2">
                        <div className="truncate">
                          {e.recurringEntryId !== null && (
                            <Repeat className="mr-1.5 inline size-3.5 align-[-2px] text-muted" aria-label={t('recurring.generated')}>
                              <title>{t('recurring.generated')}</title>
                            </Repeat>
                          )}
                          {e.description}
                        </div>
                        {e.tags.length > 0 && (
                          <div className="mt-1 flex flex-wrap gap-1">
                            {e.tags.map((name) => {
                              const id = tagId(name)
                              return <TagChip key={name} name={name} onClick={id === undefined ? undefined : () => update({ tagId: id })} />
                            })}
                          </div>
                        )}
                      </td>
                      <td className={`tabular px-2 py-2 text-right font-medium ${style.className}`}>
                        {style.sign}{money(e.amount, e.currency)}
                      </td>
                      <td className="px-2 py-2">
                        <div className="flex justify-end gap-1">
                          <button type="button" className="rounded p-1.5 text-muted hover:bg-surface hover:text-ink" aria-label={t('common.edit')}
                            onClick={() => { setEditing(e); setFormOpen(true) }}>
                            <Pencil className="size-4" />
                          </button>
                          <button type="button" className="rounded p-1.5 text-muted hover:bg-surface hover:text-bad" aria-label={t('common.delete')}
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
            <span>{t('entries.count', { count: page.totalElements })}</span>
            <div className="flex items-center gap-2">
              <Button variant="ghost" disabled={page.page === 0} onClick={() => setFilter((f) => ({ ...f, page: f.page - 1 }))} aria-label={t('entries.previousPage')}>
                <ChevronLeft className="size-4" />
              </Button>
              <span className="tabular">{page.page + 1} / {page.totalPages}</span>
              <Button variant="ghost" disabled={page.page + 1 >= page.totalPages} onClick={() => setFilter((f) => ({ ...f, page: f.page + 1 }))} aria-label={t('entries.nextPage')}>
                <ChevronRight className="size-4" />
              </Button>
            </div>
          </div>
        )}
      </Card>

      <EntryFormModal entry={editing} open={formOpen} onClose={() => setFormOpen(false)} />
      <ImportModal open={importOpen} onClose={() => setImportOpen(false)} />
    </>
  )
}
