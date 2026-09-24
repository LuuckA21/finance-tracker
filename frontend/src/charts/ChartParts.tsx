import type { ReactNode } from 'react'
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

/**
 * Horizontal bars in plain HTML for ranked lists (categories, allocation): the label and value
 * are text, the bar only shows magnitude relative to the largest item.
 */
export function RankedBars({ rows, currency, emptyText }: {
  rows: { key: string; label: string; swatch?: string; value: number; share?: number | null }[]
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
          <div className="mb-1 flex items-baseline justify-between gap-3 text-sm">
            <span className="flex min-w-0 items-center gap-2 text-ink">
              {r.swatch && <span className="size-2.5 shrink-0 rounded-full" style={{ background: r.swatch }} aria-hidden />}
              <span className="truncate">{r.label}</span>
            </span>
            <span className="tabular shrink-0 text-ink">
              {money(r.value, currency)}
              {r.share !== undefined && r.share !== null && (
                <span className="ml-2 text-xs text-muted">{percent(r.share)}</span>
              )}
            </span>
          </div>
          <div className="h-1.5 rounded-full bg-surface-2" aria-hidden>
            <div className="h-1.5 rounded-full bg-accent" style={{ width: `${max > 0 ? Math.max((r.value / max) * 100, 1) : 0}%` }} />
          </div>
        </li>
      ))}
    </ul>
  )
}
