import { useState, type FormEvent } from 'react'
import { errorMessage } from '../api/client'
import { useBulkEntries, useTags } from '../api/hooks'
import type { Category } from '../api/types'
import { CategoryOptions } from '../components/CategoryOptions'
import { TagInput } from '../components/TagInput'
import { Button, ErrorAlert, Field, Modal } from '../components/ui'
import { useI18n } from '../i18n'

/**
 * Changes the selected entries together: a category (for the entries of its kind), tags to add
 * and tags to remove. Leaving a field empty leaves that part as it is.
 */
export function BulkEditModal({ ids, categories, open, onClose, onDone }: {
  ids: number[]
  categories: Category[]
  open: boolean
  onClose: () => void
  onDone: (message: string) => void
}) {
  const { t } = useI18n()
  return (
    <Modal title={t('bulk.editTitle', { count: ids.length })} open={open} onClose={onClose}>
      {open && <BulkEditForm ids={ids} categories={categories} onDone={onDone} />}
    </Modal>
  )
}

function BulkEditForm({ ids, categories, onDone }: { ids: number[]; categories: Category[]; onDone: (message: string) => void }) {
  const { t } = useI18n()
  const bulk = useBulkEntries()
  const tagNames = useTags().data?.tags.map((tag) => tag.name) ?? []
  const [categoryId, setCategoryId] = useState<number | null>(null)
  const [add, setAdd] = useState<string[]>([])
  const [remove, setRemove] = useState<string[]>([])
  const [error, setError] = useState<string | null>(null)
  const nothing = categoryId === null && add.length === 0 && remove.length === 0

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    try {
      const result = await bulk.mutateAsync({ ids, action: 'UPDATE', categoryId, addTags: add, removeTags: remove })
      onDone(t('bulk.updated', { count: result.updated })
        + (result.skipped > 0 ? ` ${t('bulk.skipped', { count: result.skipped })}` : ''))
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4">
      <Field label={t('bulk.category')} hint={t('bulk.categoryHint')}>
        {(id) => (
          <select id={id} className="input" value={categoryId ?? ''}
            onChange={(e) => setCategoryId(e.target.value ? Number(e.target.value) : null)}>
            <option value="">{t('bulk.keep')}</option>
            <CategoryOptions categories={categories} />
          </select>
        )}
      </Field>
      <Field label={t('bulk.addTags')}>
        {(id) => <TagInput id={id} value={add} onChange={setAdd} suggestions={tagNames} />}
      </Field>
      <Field label={t('bulk.removeTags')}>
        {(id) => <TagInput id={id} value={remove} onChange={setRemove} suggestions={tagNames} />}
      </Field>
      <ErrorAlert message={error} />
      <div className="flex justify-end">
        <Button type="submit" variant="primary" loading={bulk.isPending} disabled={nothing}>
          {t('bulk.apply', { count: ids.length })}
        </Button>
      </div>
    </form>
  )
}
