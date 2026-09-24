import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { useI18n } from '../i18n'
import { compact } from '../lib/format'
import { Legend, TooltipCard } from './ChartParts'
import { flowColors, useChartTheme } from './theme'

export interface FlowDatum {
  label: string
  title: string
  income: number
  expense: number
  net: number
}

/** Income vs expenses per period as grouped columns on one axis. */
export function CashflowChart({ data, currency, height = 280 }: { data: FlowDatum[]; currency: string; height?: number }) {
  const theme = useChartTheme()
  const colors = flowColors(theme)
  const { t } = useI18n()
  return (
    <div>
      <Legend items={[
        { key: 'income', label: t('chart.income'), color: colors.income },
        { key: 'expense', label: t('chart.expense'), color: colors.expense },
      ]} />
      <div style={{ height }}>
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={data} barGap={2} barCategoryGap="28%" margin={{ top: 4, right: 4, bottom: 0, left: 0 }}>
            <CartesianGrid vertical={false} stroke={theme.grid} />
            <XAxis dataKey="label" tickLine={false} axisLine={{ stroke: theme.axis }} tick={{ fill: theme.muted, fontSize: 12 }} />
            <YAxis width={56} tickLine={false} axisLine={false} tick={{ fill: theme.muted, fontSize: 12 }}
              tickFormatter={(v: number) => compact(v)} />
            <Tooltip
              cursor={{ fill: theme.grid, opacity: 0.5 }}
              content={({ active, payload }) => {
                const d = active && payload && payload.length ? (payload[0].payload as FlowDatum) : null
                if (!d) return null
                return (
                  <TooltipCard
                    title={d.title}
                    currency={currency}
                    rows={[
                      { key: 'i', label: t('chart.income'), color: colors.income, value: d.income },
                      { key: 'e', label: t('chart.expense'), color: colors.expense, value: d.expense },
                    ]}
                    total={d.net}
                  />
                )
              }}
            />
            <Bar dataKey="income" name={t('chart.income')} fill={colors.income} maxBarSize={20} radius={[4, 4, 0, 0]} isAnimationActive={false} />
            <Bar dataKey="expense" name={t('chart.expense')} fill={colors.expense} maxBarSize={20} radius={[4, 4, 0, 0]} isAnimationActive={false} />
          </BarChart>
        </ResponsiveContainer>
      </div>
    </div>
  )
}
