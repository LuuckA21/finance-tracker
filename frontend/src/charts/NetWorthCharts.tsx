import { Area, AreaChart, Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { AssetClass, NetWorthPoint } from '../api/types'
import { ASSET_CLASS_LABEL, compact, date, periodLabel } from '../lib/format'
import { Legend, TooltipCard } from './ChartParts'
import { CLASS_ORDER, assetClassColor, useChartTheme } from './theme'

/** Total net worth over time: one 2px line with a light wash and crosshair tooltip. */
export function NetWorthLine({ points, currency, height = 260 }: { points: NetWorthPoint[]; currency: string; height?: number }) {
  const theme = useChartTheme()
  const color = theme.series[0]
  const data = points.map((p) => ({ ...p, label: periodLabel(p.period) }))
  return (
    <div style={{ height }}>
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
          <CartesianGrid vertical={false} stroke={theme.grid} />
          <XAxis dataKey="label" tickLine={false} axisLine={{ stroke: theme.axis }} tick={{ fill: theme.muted, fontSize: 12 }}
            minTickGap={16} />
          <YAxis width={56} tickLine={false} axisLine={false} tick={{ fill: theme.muted, fontSize: 12 }}
            tickFormatter={(v: number) => compact(v)} domain={['auto', 'auto']} />
          <Tooltip
            cursor={{ stroke: theme.axis, strokeWidth: 1 }}
            content={({ active, payload }) => {
              const p = active && payload && payload.length ? (payload[0].payload as NetWorthPoint) : null
              if (!p) return null
              return (
                <TooltipCard title={`Al ${date(p.date)}`} currency={currency}
                  rows={[{ key: 't', label: 'Patrimonio', color, value: p.total }]} />
              )
            }}
          />
          <Area type="monotone" dataKey="total" stroke={color} strokeWidth={2} fill={color} fillOpacity={0.1}
            dot={false} activeDot={{ r: 5, stroke: theme.surface, strokeWidth: 2, fill: color }} isAnimationActive={false} />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  )
}

/** Net worth split by asset class per period as stacked columns. */
export function NetWorthStacked({ points, currency, height = 300 }: { points: NetWorthPoint[]; currency: string; height?: number }) {
  const theme = useChartTheme()
  const present = CLASS_ORDER.filter((c) => points.some((p) => (p.byClass[c] ?? 0) !== 0))
  const data = points.map((p) => {
    const row: Record<string, number | string> = { label: periodLabel(p.period), period: p.period, date: p.date, total: p.total }
    present.forEach((c) => {
      row[c] = p.byClass[c] ?? 0
    })
    return row
  })

  return (
    <div>
      <Legend items={present.map((c) => ({ key: c, label: ASSET_CLASS_LABEL[c], color: assetClassColor(theme, c) }))} />
      <div style={{ height }}>
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={data} barCategoryGap="30%" margin={{ top: 4, right: 4, bottom: 0, left: 0 }}>
            <CartesianGrid vertical={false} stroke={theme.grid} />
            <XAxis dataKey="label" tickLine={false} axisLine={{ stroke: theme.axis }} tick={{ fill: theme.muted, fontSize: 12 }}
              minTickGap={8} />
            <YAxis width={56} tickLine={false} axisLine={false} tick={{ fill: theme.muted, fontSize: 12 }}
              tickFormatter={(v: number) => compact(v)} />
            <Tooltip
              cursor={{ fill: theme.grid, opacity: 0.5 }}
              content={({ active, payload }) => {
                const row = active && payload && payload.length ? (payload[0].payload as Record<string, number | string>) : null
                if (!row) return null
                const rows = present
                  .filter((c) => Number(row[c]) !== 0)
                  .map((c) => ({ key: c, label: ASSET_CLASS_LABEL[c], color: assetClassColor(theme, c), value: Number(row[c]) }))
                  .reverse()
                return <TooltipCard title={`Al ${date(String(row.date))}`} currency={currency} rows={rows} total={Number(row.total)} />
              }}
            />
            {present.map((c: AssetClass, i) => (
              <Bar key={c} dataKey={c} name={ASSET_CLASS_LABEL[c]} stackId="nw" fill={assetClassColor(theme, c)}
                maxBarSize={24} stroke={theme.surface} strokeWidth={1}
                radius={i === present.length - 1 ? [4, 4, 0, 0] : undefined} isAnimationActive={false} />
            ))}
          </BarChart>
        </ResponsiveContainer>
      </div>
    </div>
  )
}
