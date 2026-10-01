import { Bar, CartesianGrid, ComposedChart, Line, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { CategoryTrend } from '../api/types'
import { useI18n } from '../i18n'
import { barParts, type BarPart } from '../lib/categories'
import { compact, monthName, monthShort } from '../lib/format'
import { MAX_PARTS, partColor } from './CategoryBars'
import { Legend, TooltipCard } from './ChartParts'
import { useChartTheme } from './theme'

/** A part's amount in one month: a detail's, the folded details' together, or the macro's own. */
function monthAmount(part: BarPart, details: Record<string, number>) {
  const keys = part.role === 'rest' ? part.members.map((m) => m.key) : [part.key]
  return keys.reduce((sum, key) => sum + (details[key] ?? 0), 0)
}

/**
 * A category month by month: columns split into its details (the shades and grey of the category
 * bars), and the same month of the year before as a line on the same axis.
 */
export function CategoryTrendChart({ data, partName, height = 300 }: {
  data: CategoryTrend
  partName: (part: BarPart) => string
  height?: number
}) {
  const theme = useChartTheme()
  const { t } = useI18n()
  const parts = barParts(data.categoryId, data.details.map((d) => ({ ...d, share: null })), MAX_PARTS)
  // A detail, or a macro without details: one series
  const series = parts.length > 0
    ? parts.map((part) => ({ key: `p${part.key}`, label: partName(part), color: partColor(theme, part), part }))
    : [{ key: 'amount', label: data.name, color: theme.parts[0], part: null }]
  const previousLabel = String(data.year - 1)
  const rows = data.months.map((m) => ({
    label: monthShort(m.month - 1),
    title: `${monthName(m.month - 1)} ${data.year}`,
    amount: m.amount,
    previous: m.previous,
    ...Object.fromEntries(series.filter((s) => s.part).map((s) => [s.key, monthAmount(s.part!, m.details)])),
  }))
  return (
    <div>
      <Legend items={[
        ...series.map((s) => ({ key: s.key, label: s.label, color: s.color })),
        { key: 'previous', label: previousLabel, color: theme.ink2 },
      ]} />
      <div style={{ height }}>
        <ResponsiveContainer width="100%" height="100%">
          <ComposedChart data={rows} barCategoryGap="28%" margin={{ top: 4, right: 4, bottom: 0, left: 0 }}>
            <CartesianGrid vertical={false} stroke={theme.grid} />
            <XAxis dataKey="label" tickLine={false} axisLine={{ stroke: theme.axis }} tick={{ fill: theme.muted, fontSize: 12 }} />
            <YAxis width={56} tickLine={false} axisLine={false} tick={{ fill: theme.muted, fontSize: 12 }}
              tickFormatter={(v: number) => compact(v)} />
            <Tooltip
              cursor={{ fill: theme.grid, opacity: 0.5 }}
              content={({ active, payload }) => {
                const d = active && payload && payload.length ? (payload[0].payload as (typeof rows)[number]) : null
                if (!d) return null
                const values = d as unknown as Record<string, number>
                return (
                  <TooltipCard title={d.title} currency={data.baseCurrency} total={d.amount}
                    rows={[
                      ...(series.length > 1 ? series.map((s) => ({ key: s.key, label: s.label, color: s.color, value: values[s.key] ?? 0 })) : []),
                      { key: 'previous', label: t('trend.sameMonth', { year: data.year - 1 }), color: theme.ink2, value: d.previous },
                    ]} />
                )
              }}
            />
            {series.map((s, i) => (
              <Bar key={s.key} dataKey={s.key} name={s.label} stackId="year" fill={s.color} maxBarSize={24}
                stroke={theme.surface} strokeWidth={series.length > 1 ? 1 : 0}
                radius={i === series.length - 1 ? [4, 4, 0, 0] : 0} isAnimationActive={false} />
            ))}
            <Line dataKey="previous" name={previousLabel} stroke={theme.ink2} strokeWidth={2} isAnimationActive={false}
              dot={{ r: 3, fill: theme.ink2, stroke: theme.surface, strokeWidth: 2 }} activeDot={{ r: 5 }} />
          </ComposedChart>
        </ResponsiveContainer>
      </div>
    </div>
  )
}
