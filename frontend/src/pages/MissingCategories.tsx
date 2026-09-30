import { useState, type FormEvent } from 'react'
import { Plus } from 'lucide-react'
import { errorMessage } from '../api/client'
import { useSaveCategory } from '../api/hooks'
import type { Category, CategoryKind } from '../api/types'
import { Button, ErrorAlert, Field, Segmented } from '../components/ui'
import { useI18n } from '../i18n'
import { macros, PATH_SEPARATOR } from '../lib/categories'
import { newMacroColor, type MissingCategory } from '../lib/importCategories'

/**
 * The categories the file names but the user does not have, each creatable on the spot (as a macro,
 * or as a detail of a macro), plus any other new category to choose in the rows. Creating one hands
 * it to the rows that name it.
 */
export function MissingCategories({ missing, categories, onCreated }: {
  missing: MissingCategory[]
  categories: Category[]
  /** {@code group} null for a category created on its own */
  onCreated: (group: MissingCategory | null, category: Category) => void
}) {
  const { t } = useI18n()
  const [open, setOpen] = useState<string | null>(null)
  return (
    <section aria-labelledby="import-missing" className="rounded-lg border border-line p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 id="import-missing" className="text-sm font-medium">
          {missing.length > 0 ? t('import.missingTitle', { count: missing.length }) : t('import.newCategoryTitle')}
        </h3>
        {open !== 'new' && (
          <button type="button" className="inline-flex items-center gap-1 text-sm text-accent hover:underline" onClick={() => setOpen('new')}>
            <Plus className="size-3.5" /> {t('import.newCategory')}
          </button>
        )}
      </div>
      {missing.length > 0 && <p className="mt-1 text-xs text-ink-2">{t('import.missingHelp')}</p>}
      {open === 'new' && (
        <CategoryForm categories={categories} onCancel={() => setOpen(null)}
          onCreated={(category) => { setOpen(null); onCreated(null, category) }} />
      )}
      {missing.length > 0 && (
        <ul className="mt-2 divide-y divide-line">
          {missing.map((group) => (
            <li key={group.key} className="py-2">
              <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
                <span>
                  <span className="font-medium">{group.detail === null ? group.macro : group.macro + PATH_SEPARATOR + group.detail}</span>
                  <span className="text-muted"> · {group.kind === 'EXPENSE' ? t('entryForm.expense') : t('entryForm.income')}
                    {' · '}{t('import.missingRows', { count: group.rows.length })}</span>
                </span>
                {open !== group.key && (
                  <Button onClick={() => setOpen(group.key)}>{t('import.createCategory')}</Button>
                )}
              </div>
              {open === group.key && (
                <CategoryForm group={group} categories={categories} onCancel={() => setOpen(null)}
                  onCreated={(category) => { setOpen(null); onCreated(group, category) }} />
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

/**
 * A new category: named as in the file, under the macro the file names (a new one when the user
 * has none of that name), or at the top as a macro.
 */
function CategoryForm({ group, categories, onCancel, onCreated }: {
  group?: MissingCategory
  categories: Category[]
  onCancel: () => void
  onCreated: (category: Category) => void
}) {
  const { t } = useI18n()
  const save = useSaveCategory()
  const [kind, setKind] = useState<CategoryKind>(group?.kind ?? 'EXPENSE')
  const [name, setName] = useState(group ? group.detail ?? group.macro : '')
  // '' a macro, 'new' under the new macro the file names, else the id of one of the user's macros
  const [parent, setParent] = useState(group?.detail == null ? '' : group.macroId === null ? 'new' : String(group.macroId))
  const [error, setError] = useState<string | null>(null)
  const pending = save.isPending

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    try {
      let parentId: number | null = parent === '' || parent === 'new' ? null : Number(parent)
      let color = parentId === null ? newMacroColor(categories, kind) : categories.find((c) => c.id === parentId)?.color ?? newMacroColor(categories, kind)
      if (parent === 'new' && group) {
        const macro = await save.mutateAsync({ name: group.macro, kind, color, parentId: null })
        parentId = macro.id
        color = macro.color
      }
      onCreated(await save.mutateAsync({ name: name.trim(), kind, color, parentId }))
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  return (
    <form onSubmit={submit} className="mt-2 flex flex-col gap-3 rounded-lg bg-surface-2 p-3">
      {!group && (
        <Segmented label={t('entries.kind')} value={kind} onChange={(k) => { setKind(k); setParent('') }}
          options={[{ value: 'EXPENSE', label: t('entryForm.expense') }, { value: 'INCOME', label: t('entryForm.income') }]} />
      )}
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label={t('common.name')}>
          {(id) => <input id={id} className="input" required maxLength={64} autoFocus value={name} onChange={(e) => setName(e.target.value)} />}
        </Field>
        <Field label={t('categories.parent')}>
          {(id) => (
            <select id={id} className="input" value={parent} onChange={(e) => setParent(e.target.value)}>
              <option value="">{t('categories.noParent')}</option>
              {group && group.detail !== null && group.macroId === null && (
                <option value="new">{t('import.newMacro', { name: group.macro })}</option>
              )}
              {macros(categories, kind).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          )}
        </Field>
      </div>
      <ErrorAlert message={error} />
      <div className="flex justify-end gap-2">
        <Button onClick={onCancel} disabled={pending}>{t('common.cancel')}</Button>
        <Button type="submit" variant="primary" loading={pending}>
          {group ? t('import.createAndAssign') : t('import.create')}
        </Button>
      </div>
    </form>
  )
}
