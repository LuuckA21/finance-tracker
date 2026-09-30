import type { ReactNode } from 'react'
import { ChevronRight } from 'lucide-react'
import { useI18n } from '../i18n'
import { money, percent } from '../lib/format'

export interface TooltipRow {
  key: string
  label: string
  color: string
  value: number
}

/** Tooltip card: title line, one row per series (swatch + label + value), optional total. */
export function TooltipCard({ title, rows, currency, total }: {
  title: ReactNode
  rows: TooltipRow[]
  currency: string
  total?: number
}) {
  const { t } = useI18n()
  return (
    <div className="min-w-44 rounded-lg border border-line bg-surface px-3 py-2 text-xs shadow-lg">
      <p className="mb-1.5 font-medium text-ink">{title}</p>
      <ul className="flex flex-col gap-1">
        {rows.map((r) => (
          <li key={r.key} className="flex items-center justify-between gap-4">
            <span className="flex items-center gap-1.5 text-ink-2">
              <span className="size-2 rounded-sm" style={{ background: r.color }} aria-hidden />
              {r.label}
            </span>
            <span className="tabular text-ink">{money(r.value, currency)}</span>
          </li>
        ))}
      </ul>
      {total !== undefined && (
        <p className="mt-1.5 flex justify-between gap-4 border-t border-line pt-1.5 font-medium text-ink">
          <span>{t('common.total')}</span>
          <span className="tabular">{money(total, currency)}</span>
        </p>
      )}
    </div>
  )
}

/** Legend row shown above a chart; identity is never carried by colour alone. */
export function Legend({ items }: { items: { key: string; label: string; color: string }[] }) {
  return (
    <ul className="mb-3 flex flex-wrap gap-x-4 gap-y-1 text-xs text-ink-2">
      {items.map((i) => (
        <li key={i.key} className="flex items-center gap-1.5">
          <span className="size-2.5 rounded-sm" style={{ background: i.color }} aria-hidden />
          {i.label}
        </li>
      ))}
    </ul>
  )
}

interface RankedRow {
  key: string
  label: string
  swatch?: string
  value: number
  share?: number | null
  /** Parts of the row, shown when it is opened (a macro category's details) */
  details?: RankedRow[]
}

/**
 * Horizontal bars in plain HTML for ranked lists (categories, allocation): the label and value
 * are text, the bar only shows magnitude relative to the largest item. A row with details opens
 * on them, their bars relative to the row.
 */
export function RankedBars({ rows, currency, emptyText }: {
  rows: RankedRow[]
  currency: string
  emptyText: string
}) {
  useI18n() // re-render the formatted amounts when the language changes
  if (rows.length === 0) return <p className="py-6 text-center text-sm text-muted">{emptyText}</p>
  const max = Math.max(...rows.map((r) => r.value), 0)
  return (
    <ul className="flex flex-col gap-3">
      {rows.map((r) => (
        <li key={r.key}>
          {r.details && r.details.length > 0 ? (
            <details className="group">
              <summary className="cursor-pointer list-none rounded focus-visible:outline-2 focus-visible:outline-accent [&::-webkit-details-marker]:hidden">
                <RankedLine row={r} max={max} currency={currency} expandable />
              </summary>
              <ul className="mt-2 flex flex-col gap-2 border-l border-line pl-4">
                {r.details.map((d) => (
                  <li key={d.key}><RankedLine row={d} max={r.value} currency={currency} small /></li>
                ))}
              </ul>
            </details>
          ) : (
            <RankedLine row={r} max={max} currency={currency} />
          )}
        </li>
      ))}
    </ul>
  )
}

function RankedLine({ row, max, currency, expandable, small }: {
  row: RankedRow
  max: number
  currency: string
  expandable?: boolean
  small?: boolean
}) {
  return (
    <>
      <div className={`mb-1 flex items-baseline justify-between gap-3 ${small ? 'text-xs' : 'text-sm'}`}>
        <span className={`flex min-w-0 items-center gap-2 ${small ? 'text-ink-2' : 'text-ink'}`}>
          {expandable && <ChevronRight className="size-3.5 shrink-0 text-muted transition-transform group-open:rotate-90" aria-hidden />}
          {row.swatch && <span className={`shrink-0 rounded-full ${small ? 'size-2' : 'size-2.5'}`} style={{ background: row.swatch }} aria-hidden />}
          <span className="truncate">{row.label}</span>
        </span>
        <span className={`tabular shrink-0 ${small ? 'text-ink-2' : 'text-ink'}`}>
          {money(row.value, currency)}
          {row.share !== undefined && row.share !== null && (
            <span className="ml-2 text-xs text-muted">{percent(row.share)}</span>
          )}
        </span>
      </div>
      <div className={`rounded-full bg-surface-2 ${small ? 'h-1' : 'h-1.5'}`} aria-hidden>
        <div className={`rounded-full ${small ? 'h-1 bg-accent/70' : 'h-1.5 bg-accent'}`}
          style={{ width: `${max > 0 ? Math.max((row.value / max) * 100, 1) : 0}%` }} />
      </div>
    </>
  )
}
