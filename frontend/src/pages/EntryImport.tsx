import { useMemo, useState, type FormEvent } from 'react'
import { ChevronLeft, ChevronRight, Download, FileUp } from 'lucide-react'
import { ApiError, errorMessage, saveBlob } from '../api/client'
import { useCategories, useImportEntries, useImportPreview, useMe } from '../api/hooks'
import type { Category, EntryKind, ImportPreview, ImportPreviewRow, ImportRowError } from '../api/types'
import { Badge, Button, ErrorAlert, Field, Modal, Segmented } from '../components/ui'
import { useI18n, type MessageKey } from '../i18n'
import { date, money } from '../lib/format'

type Mode = 'all' | 'review'

/** Problems the review can fix by choosing type or category; the others need a corrected file. */
const FIXABLE: ImportRowError[] = ['invalid_kind', 'missing_category', 'unknown_category', 'category_kind_mismatch']
const REVIEW_PAGE = 100
const MAX_FILE_BYTES = 2 * 1024 * 1024

interface ReviewRow {
  source: ImportPreviewRow
  kind: EntryKind | null
  categoryId: number | null
  include: boolean
}

type Step =
  | { name: 'select' }
  | { name: 'invalid'; preview: ImportPreview }
  | { name: 'review'; preview: ImportPreview }
  | { name: 'done'; imported: number; skipped: number }

const blocking = (row: ImportPreviewRow) => row.errors.filter((e) => !FIXABLE.includes(e))

function isReady(row: ReviewRow, categories: Category[]) {
  return blocking(row.source).length === 0 && row.kind !== null && row.categoryId !== null
    && categories.some((c) => c.id === row.categoryId && c.kind === row.kind)
}

export function ImportModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useI18n()
  const [step, setStep] = useState<Step>({ name: 'select' })

  function close() {
    setStep({ name: 'select' })
    onClose()
  }

  return (
    <Modal title={t('import.title')} open={open} onClose={close} wide={step.name === 'review' ? 'xl' : true}>
      {open && step.name === 'select' && <SelectStep onStep={setStep} />}
      {open && step.name === 'invalid' && (
        <InvalidStep preview={step.preview} onReview={() => setStep({ name: 'review', preview: step.preview })}
          onBack={() => setStep({ name: 'select' })} />
      )}
      {open && step.name === 'review' && <ReviewStep preview={step.preview} onStep={setStep} />}
      {open && step.name === 'done' && (
        <div className="flex flex-col gap-4">
          <p className="rounded-lg bg-surface-2 px-3 py-3 text-sm">
            {step.imported === 0 ? t('import.nothingNew') : t('import.done', { count: step.imported })}
            {step.imported > 0 && step.skipped > 0 && <> {t('import.doneSkipped', { count: step.skipped })}</>}
          </p>
          <div className="flex justify-end"><Button variant="primary" onClick={close}>{t('common.close')}</Button></div>
        </div>
      )}
    </Modal>
  )
}

function SelectStep({ onStep }: { onStep: (step: Step) => void }) {
  const { t, language } = useI18n()
  const categories = useCategories().data ?? []
  const preview = useImportPreview()
  const save = useImportEntries()
  const [file, setFile] = useState<File | null>(null)
  const [mode, setMode] = useState<Mode>('review')
  const [error, setError] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    if (!file) return
    if (file.size > MAX_FILE_BYTES) {
      setError(t('error.csv_too_large'))
      return
    }
    try {
      const result = await preview.mutateAsync(file)
      if (mode === 'review') {
        onStep({ name: 'review', preview: result })
        return
      }
      if (result.invalid > 0) {
        onStep({ name: 'invalid', preview: result })
        return
      }
      const rows = result.rows.filter((r) => !r.duplicate)
      if (rows.length > 0) await save.mutateAsync(rows.map(toInput))
      onStep({ name: 'done', imported: rows.length, skipped: result.rows.length - rows.length })
    } catch (err) {
      setError(fileErrorMessage(err, t))
    }
  }

  function downloadTemplate() {
    const expense = categories.find((c) => c.kind === 'EXPENSE')?.name ?? ''
    const income = categories.find((c) => c.kind === 'INCOME')?.name ?? ''
    const csv = language === 'EN'
      ? `date;type;category;amount;currency;description\r\n2026-08-01;Expense;${expense};45.20;CHF;Supermarket\r\n2026-08-25;Income;${income};6000;CHF;\r\n`
      : `data;tipo;categoria;importo;valuta;descrizione\r\n2026-08-01;Uscita;${expense};45.20;CHF;Supermercato\r\n2026-08-25;Entrata;${income};6000;CHF;\r\n`
    saveBlob(new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' }), language === 'EN' ? 'template.csv' : 'modello.csv')
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4">
      <div className="text-sm text-ink-2">
        <p>{t('import.help')}</p>
        <ul className="mt-2 list-disc space-y-0.5 pl-5 text-xs">
          <li>{t('import.helpColumns')}</li>
          <li>{t('import.helpFormats')}</li>
          <li>{t('import.helpKind')}</li>
        </ul>
        <button type="button" onClick={downloadTemplate} className="mt-2 inline-flex items-center gap-1 text-xs font-medium text-accent hover:underline">
          <Download className="size-3.5" /> {t('import.template')}
        </button>
      </div>
      <Field label={t('import.file')} hint={t('import.fileHint')}>
        {(id) => (
          <input id={id} type="file" accept=".csv,text/csv" required className="input file:mr-3 file:rounded-md file:border-0 file:bg-surface-2 file:px-2 file:py-1 file:text-sm"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
        )}
      </Field>
      <div className="flex flex-col gap-1">
        <Segmented label={t('import.mode')} value={mode} onChange={setMode}
          options={[{ value: 'review', label: t('import.modeReview') }, { value: 'all', label: t('import.modeAll') }]} />
        <p className="text-xs text-muted">{mode === 'review' ? t('import.modeReviewHint') : t('import.modeAllHint')}</p>
      </div>
      <ErrorAlert message={error} />
      <div className="flex justify-end">
        <Button type="submit" variant="primary" loading={preview.isPending || save.isPending} disabled={!file}>
          <FileUp className="size-4" /> {mode === 'review' ? t('import.analyze') : t('import.importAll')}
        </Button>
      </div>
    </form>
  )
}

function InvalidStep({ preview, onReview, onBack }: { preview: ImportPreview; onReview: () => void; onBack: () => void }) {
  const { t } = useI18n()
  const invalid = preview.rows.filter((r) => r.errors.length > 0)
  return (
    <div className="flex flex-col gap-4">
      <p className="rounded-lg bg-bad-soft px-3 py-2 text-sm text-bad">
        {t('import.invalidSummary', { invalid: preview.invalid, total: preview.total })}
      </p>
      <ul className="max-h-72 divide-y divide-line overflow-y-auto text-sm">
        {invalid.slice(0, 50).map((row) => (
          <li key={row.line} className="py-1.5">
            <span className="font-medium">{t('import.line', { line: row.line })}</span>{' '}
            <span className="text-ink-2">{row.errors.map((e) => rowErrorText(e, row, t)).join(' · ')}</span>
          </li>
        ))}
        {invalid.length > 50 && <li className="py-1.5 text-muted">{t('import.andMore', { count: invalid.length - 50 })}</li>}
      </ul>
      <div className="flex flex-wrap justify-end gap-2">
        <Button onClick={onBack}>{t('import.otherFile')}</Button>
        <Button variant="primary" onClick={onReview}>{t('import.toReview')}</Button>
      </div>
    </div>
  )
}

function ReviewStep({ preview, onStep }: { preview: ImportPreview; onStep: (step: Step) => void }) {
  const { t } = useI18n()
  const categories = useCategories().data ?? []
  const baseCurrency = useMe().data?.baseCurrency ?? 'CHF'
  const save = useImportEntries()
  const [error, setError] = useState<string | null>(null)
  const [page, setPage] = useState(0)
  const [onlyToCheck, setOnlyToCheck] = useState(false)
  const [rows, setRows] = useState<ReviewRow[]>(() => preview.rows.map((source) => {
    const row: ReviewRow = { source, kind: source.kind, categoryId: source.categoryId, include: false }
    return { ...row, include: isReady(row, categories) && !source.duplicate }
  }))

  const visible = useMemo(() => rows
    .map((row, index) => ({ row, index }))
    .filter(({ row }) => !onlyToCheck || row.source.duplicate || row.source.errors.length > 0), [rows, onlyToCheck])
  const pages = Math.max(1, Math.ceil(visible.length / REVIEW_PAGE))
  const shown = visible.slice(page * REVIEW_PAGE, (page + 1) * REVIEW_PAGE)
  const selected = rows.filter((r) => r.include && isReady(r, categories))

  function change(index: number, patch: Partial<ReviewRow>) {
    setRows((all) => all.map((row, i) => {
      if (i !== index) return row
      const next = { ...row, ...patch }
      // A category of the other type no longer fits
      if (patch.kind && !categories.some((c) => c.id === next.categoryId && c.kind === patch.kind)) next.categoryId = null
      if (patch.kind !== undefined || patch.categoryId !== undefined) next.include = isReady(next, categories)
      return next
    }))
  }

  function selectAll(include: boolean) {
    setRows((all) => all.map((row) => ({ ...row, include: include && isReady(row, categories) && !row.source.duplicate })))
  }

  async function submit() {
    setError(null)
    try {
      await save.mutateAsync(selected.map((r) => toInput({ ...r.source, kind: r.kind, categoryId: r.categoryId })))
      onStep({ name: 'done', imported: selected.length, skipped: rows.length - selected.length })
    } catch (err) {
      const row = err instanceof ApiError && typeof err.row === 'number' ? selected[err.row] : undefined
      setError(row ? `${t('import.line', { line: row.source.line })}: ${errorMessage(err)}` : errorMessage(err))
    }
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2 text-xs">
        <Badge>{t('import.rows', { count: preview.total })}</Badge>
        <Badge tone="good">{t('import.validCount', { count: preview.valid })}</Badge>
        {preview.duplicates > 0 && <Badge tone="accent">{t('import.duplicateCount', { count: preview.duplicates })}</Badge>}
        {preview.invalid > 0 && <Badge tone="bad">{t('import.invalidCount', { count: preview.invalid })}</Badge>}
        {preview.ignoredColumns.length > 0 && (
          <span className="text-muted">{t('import.ignoredColumns', { columns: preview.ignoredColumns.join(', ') })}</span>
        )}
      </div>
      <p className="text-xs text-ink-2">{t('import.reviewHelp')}</p>
      <div className="flex flex-wrap items-center gap-3 text-sm">
        <button type="button" className="text-accent hover:underline" onClick={() => selectAll(true)}>{t('import.selectReady')}</button>
        <button type="button" className="text-accent hover:underline" onClick={() => selectAll(false)}>{t('import.selectNone')}</button>
        <label className="flex items-center gap-1.5">
          <input type="checkbox" checked={onlyToCheck} onChange={(e) => { setOnlyToCheck(e.target.checked); setPage(0) }} />
          {t('import.onlyToCheck')}
        </label>
      </div>

      <div className="-mx-5 overflow-x-auto sm:mx-0">
        <table className="w-full min-w-[56rem] text-sm">
          <thead>
            <tr className="border-b border-line text-left text-xs text-muted">
              <th className="w-8 px-2 py-2"><span className="sr-only">{t('import.include')}</span></th>
              <th className="px-2 py-2 font-medium">{t('import.lineShort')}</th>
              <th className="px-2 py-2 font-medium">{t('common.date')}</th>
              <th className="px-2 py-2 font-medium">{t('entries.kind')}</th>
              <th className="px-2 py-2 font-medium">{t('entries.category')}</th>
              <th className="px-2 py-2 text-right font-medium">{t('common.amount')}</th>
              <th className="px-2 py-2 font-medium">{t('entryForm.descriptionOptional')}</th>
              <th className="px-2 py-2 font-medium">{t('import.notes')}</th>
            </tr>
          </thead>
          <tbody>
            {shown.map(({ row, index }) => {
              const fatal = blocking(row.source)
              const ready = isReady(row, categories)
              const options = categories.filter((c) => c.kind === row.kind)
              return (
                <tr key={row.source.line} className={`border-b border-line align-top last:border-0 ${fatal.length > 0 ? 'bg-bad-soft/40' : ''}`}>
                  <td className="px-2 py-1.5">
                    <input type="checkbox" aria-label={t('import.includeLine', { line: row.source.line })}
                      checked={row.include && ready} disabled={!ready}
                      onChange={(e) => change(index, { include: e.target.checked })} />
                  </td>
                  <td className="tabular px-2 py-1.5 text-muted">{row.source.line}</td>
                  <td className="tabular px-2 py-1.5">{row.source.date ? date(row.source.date) : <span className="text-bad">{row.source.raw.date || '—'}</span>}</td>
                  <td className="px-2 py-1.5">
                    <select className="input py-1" aria-label={t('entries.kind')} value={row.kind ?? ''} disabled={fatal.length > 0}
                      onChange={(e) => change(index, { kind: (e.target.value || null) as EntryKind | null })}>
                      {row.kind === null && <option value="">—</option>}
                      <option value="EXPENSE">{t('entryForm.expense')}</option>
                      <option value="INCOME">{t('entryForm.income')}</option>
                    </select>
                  </td>
                  <td className="px-2 py-1.5">
                    <select className={`input py-1 ${row.categoryId === null && fatal.length === 0 ? 'border-bad' : ''}`}
                      aria-label={t('entries.category')} value={row.categoryId ?? ''} disabled={fatal.length > 0 || row.kind === null}
                      onChange={(e) => change(index, { categoryId: e.target.value ? Number(e.target.value) : null })}>
                      <option value="">{t('entryForm.choose')}</option>
                      {options.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
                    </select>
                    {row.source.raw.category && row.categoryId === null && (
                      <p className="mt-0.5 text-xs text-muted">{t('import.inFile', { value: row.source.raw.category })}</p>
                    )}
                  </td>
                  <td className="tabular px-2 py-1.5 text-right">
                    {row.source.amount !== null
                      ? money(row.source.amount, row.source.currency ?? baseCurrency)
                      : <span className="text-bad">{row.source.raw.amount || '—'}</span>}
                  </td>
                  <td className="max-w-[16rem] truncate px-2 py-1.5 text-ink-2" title={row.source.description ?? ''}>{row.source.description ?? ''}</td>
                  <td className="px-2 py-1.5">
                    <div className="flex flex-wrap gap-1">
                      {row.source.duplicate && <Badge tone="accent">{t('import.duplicate')}</Badge>}
                      {row.source.errors.filter((e) => !FIXABLE.includes(e) || !ready).map((e) => (
                        <Badge key={e} tone={FIXABLE.includes(e) ? 'neutral' : 'bad'}>{rowErrorText(e, row.source, t)}</Badge>
                      ))}
                    </div>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      {pages > 1 && (
        <div className="flex items-center justify-end gap-2 text-sm">
          <Button onClick={() => setPage((p) => p - 1)} disabled={page === 0} aria-label={t('import.previousPage')}><ChevronLeft className="size-4" /></Button>
          <span className="tabular text-ink-2">{page + 1} / {pages}</span>
          <Button onClick={() => setPage((p) => p + 1)} disabled={page >= pages - 1} aria-label={t('import.nextPage')}><ChevronRight className="size-4" /></Button>
        </div>
      )}

      <ErrorAlert message={error} />
      <div className="flex flex-wrap items-center justify-end gap-3 border-t border-line pt-3">
        <span className="text-sm text-ink-2">{t('import.selected', { count: selected.length, total: rows.length })}</span>
        <Button onClick={() => onStep({ name: 'select' })}>{t('import.otherFile')}</Button>
        <Button variant="primary" onClick={submit} loading={save.isPending} disabled={selected.length === 0}>
          {t('import.importSelected', { count: selected.length })}
        </Button>
      </div>
    </div>
  )
}

function toInput(row: Pick<ImportPreviewRow, 'date' | 'kind' | 'categoryId' | 'amount' | 'currency' | 'description'>) {
  return {
    date: row.date!,
    kind: row.kind!,
    categoryId: row.categoryId!,
    amount: row.amount!,
    currency: row.currency!,
    description: row.description,
  }
}

type Translate = ReturnType<typeof useI18n>['t']

function rowErrorText(error: ImportRowError, row: ImportPreviewRow, t: Translate) {
  return t(`import.error.${error}` as MessageKey, { value: row.raw.category ?? row.raw.kind ?? '' })
}

/** File-level problems (not a CSV, missing columns, too large) with the line when known. */
function fileErrorMessage(err: unknown, t: Translate) {
  const message = errorMessage(err)
  if (err instanceof ApiError && err.line) return `${message} (${t('import.line', { line: err.line })})`
  if (err instanceof ApiError && err.columns?.length) return `${message} ${err.columns.join(', ')}`
  return message
}
