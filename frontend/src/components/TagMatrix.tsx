import { useState } from 'react'
import type { CashflowYear } from '../api/types'
import { useI18n } from '../i18n'
import { number } from '../lib/format'
import { Card, Segmented } from './ui'

type Kind = CashflowYear['tagMatrices'][number]['kind']

/** Whole units without the currency, which the note below the table gives once: narrower columns. */
const amount = (value: number | undefined) => number(value, 0)

/**
 * The year's income or expenses of every category against the largest tags, the other tags and no tag.
 * The category column stays in place while the table scrolls sideways on small screens.
 */
export function TagMatrixCard({ data, currency }: { data: CashflowYear; currency: string }) {
  const { t } = useI18n()
  const [kind, setKind] = useState<Kind>(() => (data.tagMatrices.some((m) => m.kind === 'EXPENSE') ? 'EXPENSE' : 'INCOME'))
  const matrix = data.tagMatrices.find((m) => m.kind === kind)
  const categories = new Map(data.categories.filter((c) => c.kind === kind).map((c) => [c.categoryId, c]))
  const tags = new Map(data.tags.filter((r) => r.kind === kind).map((r) => [r.tagId, r]))
  const total = kind === 'EXPENSE' ? data.totals.expense : data.totals.income
  const showOther = !!matrix && matrix.otherTags > 0
  // The largest cell sets the strongest shade; totals are not shaded
  const max = matrix ? Math.max(0, ...matrix.rows.flatMap((r) => [...Object.values(r.tags), r.otherTags, r.untagged])) : 0
  const cell = (value: number | undefined, key: string) => (
    <td key={key} className="tabular whitespace-nowrap px-2 py-2 text-right"
      style={value && max > 0 ? { background: `color-mix(in srgb, var(--color-accent) ${Math.round(6 + 24 * (value / max))}%, transparent)` } : undefined}>
      {value ? amount(value) : <span className="text-muted">—</span>}
    </td>
  )
  const sticky = 'sticky left-0 z-10 bg-surface px-4 py-2 text-left sm:px-2'

  return (
    <Card title={t('cashflow.tagMatrix')} className="mb-4"
      actions={<Segmented label={t('cashflow.matrixKind')} value={kind} onChange={setKind}
        options={[{ value: 'EXPENSE', label: t('cashflow.colExpense') }, { value: 'INCOME', label: t('cashflow.colIncome') }]} />}>
      {!matrix ? (
        <p className="py-6 text-center text-sm text-muted">{kind === 'EXPENSE' ? t('cashflow.noTaggedExpenses') : t('cashflow.noTaggedIncome')}</p>
      ) : (
        <>
          <div className="relative -mx-4 overflow-x-auto sm:mx-0">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line text-xs text-muted">
                  <th scope="col" className={`${sticky} font-medium`}>{t('cashflow.colCategory')}</th>
                  {matrix.tagIds.map((id) => (
                    <th key={id} scope="col" className="whitespace-nowrap px-2 py-2 text-right font-medium">{tags.get(id)?.name ?? '—'}</th>
                  ))}
                  {showOther && <th scope="col" className="whitespace-nowrap px-2 py-2 text-right font-medium">{t('cashflow.otherTags')}</th>}
                  <th scope="col" className="whitespace-nowrap px-2 py-2 text-right font-medium">{t('cashflow.untagged')}</th>
                  <th scope="col" className="px-2 py-2 text-right font-medium">{t('common.total')}</th>
                </tr>
              </thead>
              <tbody>
                {matrix.rows.map((row) => {
                  const category = categories.get(row.categoryId)
                  return (
                    <tr key={row.categoryId} className="border-b border-line last:border-0">
                      <th scope="row" className={`${sticky} font-normal`}>
                        <span className="flex items-center gap-2 whitespace-nowrap">
                          <span className="size-2.5 shrink-0 rounded-full" style={{ background: category?.color }} aria-hidden />
                          {category?.name ?? '—'}
                        </span>
                      </th>
                      {matrix.tagIds.map((id) => cell(row.tags[String(id)], String(id)))}
                      {showOther && cell(row.otherTags, 'other')}
                      {cell(row.untagged, 'none')}
                      <td className="tabular whitespace-nowrap px-2 py-2 text-right font-medium">{amount(row.total)}</td>
                    </tr>
                  )
                })}
              </tbody>
              <tfoot>
                <tr className="border-t-2 border-line font-semibold">
                  <th scope="row" className={sticky}>{t('common.total')}</th>
                  {matrix.tagIds.map((id) => (
                    <td key={id} className="tabular whitespace-nowrap px-2 py-2 text-right">{amount(tags.get(id)?.amount)}</td>
                  ))}
                  {showOther && <td className="tabular whitespace-nowrap px-2 py-2 text-right">{amount(matrix.otherTags)}</td>}
                  <td className="tabular whitespace-nowrap px-2 py-2 text-right">{amount(matrix.untagged)}</td>
                  <td className="tabular whitespace-nowrap px-2 py-2 text-right">{amount(total)}</td>
                </tr>
              </tfoot>
            </table>
          </div>
          <p className="mt-3 text-xs text-muted">{t('cashflow.amountsIn', { currency })} {t('cashflow.matrixHelp')}</p>
        </>
      )}
    </Card>
  )
}
