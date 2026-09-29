import { useMemo, useState, type FormEvent } from 'react'
import { Pencil, Trash2 } from 'lucide-react'
import { errorMessage } from '../../api/client'
import { useCategories, useDeleteTag, useRenameTag, useTags } from '../../api/hooks'
import type { Category, TagSummary } from '../../api/types'
import { TagCategories } from '../../components/TagCategories'
import { MAX_TAG_LENGTH } from '../../components/TagInput'
import { Button, Card, EmptyState, ErrorAlert, Field, MissingRatesNotice, Modal, Spinner } from '../../components/ui'
import { useI18n } from '../../i18n'
import { date, money } from '../../lib/format'

/** Stable while categories load, so the lookup below is not rebuilt on every render. */
const NO_CATEGORIES: Category[] = []

/** The user's tags with their totals: rename or delete them (their entries stay). */
export function TagsTab() {
  const tags = useTags()
  const categories = useCategories().data ?? NO_CATEGORIES
  const byId = useMemo(() => new Map(categories.map((c) => [c.id, c])), [categories])
  const remove = useDeleteTag()
  const [renaming, setRenaming] = useState<TagSummary | null>(null)
  const [error, setError] = useState<string | null>(null)
  const { t } = useI18n()
  const currency = tags.data?.baseCurrency ?? 'CHF'

  async function onDelete(tag: TagSummary) {
    if (!confirm(t('tags.confirmDelete', { name: tag.name }))) return
    setError(null)
    try {
      await remove.mutateAsync(tag.id)
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  if (tags.isPending) return <Spinner />
  const rows = tags.data?.tags ?? []
  return (
    <>
      <ErrorAlert message={error} />
      <MissingRatesNotice currencies={tags.data?.unconvertedCurrencies ?? []} baseCurrency={currency} />
      <Card title={t('tags.title')}>
        <p className="mb-3 text-sm text-ink-2">{t('tags.help')}</p>
        {rows.length === 0 ? (
          <EmptyState title={t('tags.emptyTitle')}>{t('tags.emptyHelp')}</EmptyState>
        ) : (
          <ul className="divide-y divide-line">
            {rows.map((tag) => (
              <li key={tag.id} className="flex items-start justify-between gap-2 py-2.5 text-sm">
                <div className="flex min-w-0 flex-col gap-1">
                  <p className="font-medium">{tag.name}</p>
                  <p className="flex flex-wrap gap-x-3 text-xs text-ink-2">
                    <span>{t('tags.entries', { count: tag.entryCount })}</span>
                    {tag.expense > 0 && <span>{t('tags.expense', { amount: money(tag.expense, currency) })}</span>}
                    {tag.income > 0 && <span>{t('tags.income', { amount: money(tag.income, currency) })}</span>}
                    {tag.transferred > 0 && <span>{t('tags.transferred', { amount: money(tag.transferred, currency) })}</span>}
                    {tag.firstDate && (
                      <span className="text-muted">{tag.firstDate === tag.lastDate ? date(tag.firstDate) : `${date(tag.firstDate)} – ${date(tag.lastDate)}`}</span>
                    )}
                  </p>
                  <TagCategories rows={tag.categories} categories={byId} currency={currency} />
                </div>
                <span className="flex shrink-0 gap-1">
                  <button type="button" className="rounded p-1.5 text-muted hover:text-ink" aria-label={t('tags.rename', { name: tag.name })} onClick={() => setRenaming(tag)}>
                    <Pencil className="size-4" />
                  </button>
                  <button type="button" className="rounded p-1.5 text-muted hover:text-bad" aria-label={t('tags.delete', { name: tag.name })} onClick={() => onDelete(tag)}>
                    <Trash2 className="size-4" />
                  </button>
                </span>
              </li>
            ))}
          </ul>
        )}
      </Card>
      <Modal title={t('tags.renameTitle')} open={renaming !== null} onClose={() => setRenaming(null)}>
        {renaming && <RenameForm tag={renaming} onDone={() => setRenaming(null)} />}
      </Modal>
    </>
  )
}

function RenameForm({ tag, onDone }: { tag: TagSummary; onDone: () => void }) {
  const rename = useRenameTag()
  const [name, setName] = useState(tag.name)
  const [error, setError] = useState<string | null>(null)
  const { t } = useI18n()

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    try {
      await rename.mutateAsync({ id: tag.id, name: name.trim() })
      onDone()
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4">
      <Field label={t('common.name')} hint={t('tags.renameHint')}>
        {(id) => <input id={id} className="input" required maxLength={MAX_TAG_LENGTH} autoFocus value={name} onChange={(e) => setName(e.target.value)} />}
      </Field>
      <ErrorAlert message={error} />
      <div className="flex justify-end">
        <Button type="submit" variant="primary" loading={rename.isPending}>{t('common.save')}</Button>
      </div>
    </form>
  )
}
