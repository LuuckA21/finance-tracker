import { ChevronRight } from 'lucide-react'
import type { CashflowYear } from '../api/types'
import { useI18n } from '../i18n'
import { barParts, type BarPart } from '../lib/categories'
import { money, percent } from '../lib/format'
import { useChartTheme, type ChartTheme } from './theme'

type Row = CashflowYear['categories'][number]

/** Details shown as parts of a bar, one per shade; past it the smallest share the last one. */
const MAX_PARTS = 3

/**
 * A kind's macro categories as horizontal bars, largest first, on one scale. A macro with details
 * shows them as parts of its bar (one hue, the largest in the bar colour; its own entries in grey) and opens
 * on the list of them, which is also the bar's legend.
 */
export function CategoryBars({ rows, currency, emptyText }: { rows: Row[]; currency: string; emptyText: string }) {
  const { t } = useI18n()
  const theme = useChartTheme()
  if (rows.length === 0) return <p className="py-6 text-center text-sm text-muted">{emptyText}</p>
  const max = Math.max(...rows.map((r) => r.amount), 0)
  const hasDetails = rows.some((r) => barParts(r.categoryId, r.details, MAX_PARTS).length > 0)
  const partName = (row: Row, part: BarPart) => part.role === 'own' ? t('categories.withoutDetail', { name: row.name })
    : part.role === 'rest' ? t('cashflow.otherDetails', { count: part.count }) : part.name
  return (
    <>
      <ul className="flex flex-col gap-3">
        {rows.map((row) => {
          const parts = barParts(row.categoryId, row.details, MAX_PARTS)
          const width = max > 0 ? Math.max((row.amount / max) * 100, 1) : 0
          // Names line up whether or not a row opens
          const header = (expandable: boolean) => (
            <div className="mb-1 flex items-baseline justify-between gap-3 text-sm">
              <span className="flex min-w-0 items-center gap-2 text-ink">
                {expandable ? <ChevronRight className="size-3.5 shrink-0 self-center text-muted transition-transform group-open:rotate-90" aria-hidden />
                  : hasDetails && <span className="size-3.5 shrink-0" aria-hidden />}
                <span className="size-2.5 shrink-0 self-center rounded-full" style={{ background: row.color }} aria-hidden />
                <span className="truncate">{row.name}</span>
              </span>
              <span className="tabular shrink-0 text-ink">
                {money(row.amount, currency)}
                {row.share !== null && <span className="ml-2 text-xs text-muted">{percent(row.share)}</span>}
              </span>
            </div>
          )
          const bar = (
            <div className="h-2.5 rounded-r bg-surface-2" aria-hidden>
              <div className="flex h-2.5 gap-[2px] overflow-hidden rounded-r bg-surface" style={{ width: `${width}%` }}>
                {parts.length === 0 ? <div className="h-full flex-1" style={{ background: theme.parts[0] }} /> : parts.map((part) => (
                  <div key={part.key} className="h-full min-w-[2px] transition-[filter] hover:brightness-110"
                    style={{ flexGrow: part.amount, flexBasis: 0, background: partColor(theme, part) }}
                    title={`${partName(row, part)} · ${money(part.amount, currency)}${part.share === null ? '' : ` · ${percent(part.share)}`}`} />
                ))}
              </div>
            </div>
          )
          return (
            <li key={row.categoryId}>
              {parts.length === 0 ? <>{header(false)}{bar}</> : (
                <details className="group">
                  <summary className="cursor-pointer list-none rounded focus-visible:outline-2 focus-visible:outline-accent [&::-webkit-details-marker]:hidden">
                    {header(true)}
                    {bar}
                  </summary>
                  {/* The bar's legend and its values: every detail, in the colour of its part */}
                  <ul className="mt-2 flex flex-col gap-1.5 pl-6">
                    {parts.flatMap((part) => (part.role === 'rest' ? part.members : [{ ...part, name: partName(row, part) }])
                      .map((line) => (
                        <li key={line.key} className="flex items-baseline justify-between gap-3 text-xs">
                          <span className="flex min-w-0 items-center gap-2 text-ink-2">
                            <span className="size-2.5 shrink-0 self-center rounded-sm" style={{ background: partColor(theme, part) }} aria-hidden />
                            <span className="truncate">{line.name}</span>
                          </span>
                          <span className="tabular shrink-0 text-ink-2">
                            {money(line.amount, currency)}
                            {line.share !== null && <span className="ml-2 text-muted">{percent(line.share)}</span>}
                          </span>
                        </li>
                      )))}
                  </ul>
                </details>
              )}
            </li>
          )
        })}
      </ul>
      {hasDetails && <p className="mt-3 text-xs text-muted">{t('cashflow.categoriesHelp')}</p>}
    </>
  )
}

function partColor(theme: ChartTheme, part: BarPart) {
  return part.role === 'own' ? theme.other : theme.parts[Math.min(part.step, theme.parts.length - 1)]
}
