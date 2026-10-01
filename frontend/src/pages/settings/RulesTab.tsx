import { useMemo, useState, type FormEvent } from 'react'
import { ArrowRight, Pencil, Trash2 } from 'lucide-react'
import { errorMessage } from '../../api/client'
import { useCategories, useCategoryRules, useDeleteCategoryRule, useSaveCategoryRule } from '../../api/hooks'
import type { Category, CategoryRule } from '../../api/types'
import { CategoryOptions } from '../../components/CategoryOptions'
import { Button, Card, EmptyState, ErrorAlert, Field, Modal, Spinner } from '../../components/ui'
import { useI18n } from '../../i18n'
import { categoryPath } from '../../lib/categories'

/** Stable while categories load, so the lookup below is not rebuilt on every render. */
const NO_CATEGORIES: Category[] = []

/** The user's rules: a text the description contains and the category it gets. */
export function RulesTab() {
  const { t } = useI18n()
  const rules = useCategoryRules()
  const categories = useCategories().data ?? NO_CATEGORIES
  const byId = useMemo(() => new Map(categories.map((c) => [c.id, c])), [categories])
  const remove = useDeleteCategoryRule()
  const [editing, setEditing] = useState<CategoryRule | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function onDelete(rule: CategoryRule) {
    if (!confirm(t('rules.confirmDelete', { pattern: rule.pattern }))) return
    setError(null)
    try {
      await remove.mutateAsync(rule.id)
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  if (rules.isPending) return <Spinner />
  const list = (rules.data ?? []).toSorted((a, b) => a.pattern.localeCompare(b.pattern))
  return (
    <>
      <p className="mt-2 text-sm text-ink-2">{t('rules.help')}</p>
      <ErrorAlert message={error} />
      <div className="mt-3 grid gap-4 lg:grid-cols-[1fr_2fr]">
        <Card title={t('rules.new')}>
          <RuleForm rule={null} categories={categories} onDone={() => undefined} />
        </Card>
        <Card title={t('rules.title', { count: list.length })}>
          {list.length === 0 ? <EmptyState title={t('rules.emptyTitle')}>{t('rules.emptyHelp')}</EmptyState> : (
            <ul className="divide-y divide-line">
              {list.map((rule) => {
                const category = byId.get(rule.categoryId)
                return (
                  <li key={rule.id} className="flex items-center justify-between gap-3 py-2 text-sm">
                    <span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5">
                      <span className="font-medium">«{rule.pattern}»</span>
                      <ArrowRight className="size-3.5 shrink-0 text-muted" aria-label={t('rules.goesTo')} />
                      <span className="inline-flex min-w-0 items-center gap-1.5 text-ink-2">
                        <span className="size-2.5 shrink-0 rounded-full" style={{ background: category?.color }} aria-hidden />
                        <span className="truncate">{categoryPath(rule.categoryId, byId) || '—'}</span>
                      </span>
                    </span>
                    <span className="flex shrink-0 gap-1">
                      <button type="button" className="rounded p-1.5 text-muted hover:text-ink" aria-label={t('rules.edit', { pattern: rule.pattern })}
                        onClick={() => setEditing(rule)}>
                        <Pencil className="size-4" />
                      </button>
                      <button type="button" className="rounded p-1.5 text-muted hover:text-bad" aria-label={t('rules.delete', { pattern: rule.pattern })}
                        onClick={() => onDelete(rule)}>
                        <Trash2 className="size-4" />
                      </button>
                    </span>
                  </li>
                )
              })}
            </ul>
          )}
        </Card>
      </div>
      <Modal title={t('rules.editTitle')} open={editing !== null} onClose={() => setEditing(null)}>
        {editing && <RuleForm rule={editing} categories={categories} onDone={() => setEditing(null)} />}
      </Modal>
    </>
  )
}

/** A new rule, or one being changed; a new one clears itself once saved, ready for the next. */
export function RuleForm({ rule, categories, initialPattern = '', initialCategoryId = null, onDone, onCancel }: {
  rule: CategoryRule | null
  categories: Category[]
  initialPattern?: string
  initialCategoryId?: number | null
  onDone: (rule: CategoryRule) => void
  onCancel?: () => void
}) {
  const { t } = useI18n()
  const save = useSaveCategoryRule()
  const [pattern, setPattern] = useState(rule?.pattern ?? initialPattern)
  const [categoryId, setCategoryId] = useState<number | ''>(rule?.categoryId ?? initialCategoryId ?? '')
  const [error, setError] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    if (categoryId === '') return
    try {
      const saved = await save.mutateAsync({ id: rule?.id, pattern, categoryId })
      if (!rule) {
        setPattern('')
        setCategoryId('')
      }
      onDone(saved)
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-3">
      <Field label={t('rules.pattern')} hint={t('rules.patternHint')}>
        {(id) => <input id={id} className="input" required maxLength={100} value={pattern} onChange={(e) => setPattern(e.target.value)} />}
      </Field>
      <Field label={t('entries.category')}>
        {(id) => (
          <select id={id} className="input" required value={categoryId} onChange={(e) => setCategoryId(e.target.value ? Number(e.target.value) : '')}>
            <option value="">{t('entryForm.choose')}</option>
            <CategoryOptions categories={categories} />
          </select>
        )}
      </Field>
      <ErrorAlert message={error} />
      <div className="flex justify-end gap-2">
        {onCancel && <Button onClick={onCancel} disabled={save.isPending}>{t('common.cancel')}</Button>}
        <Button type="submit" variant="primary" loading={save.isPending}>{rule ? t('common.save') : t('rules.add')}</Button>
      </div>
    </form>
  )
}
