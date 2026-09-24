import { useState } from 'react'
import { useCashflowYear, useCashflowYears, useMe } from '../api/hooks'
import { Card, MissingRatesNotice, PageHeader, Segmented, Spinner, StatTile } from '../components/ui'
import { CashflowChart } from '../charts/CashflowChart'
import { RankedBars } from '../charts/ChartParts'
import { useI18n } from '../i18n'
import { money, monthName, monthShort, percent } from '../lib/format'
import type { CashflowTotals } from '../api/types'

export function CashflowPage() {
  const [year, setYear] = useState(new Date().getFullYear())
  const [view, setView] = useState<'MONTH' | 'YEAR'>('MONTH')
  const me = useMe().data
  const { t } = useI18n()
  const data = useCashflowYear(year)
  const years = useCashflowYears()
  const currency = data.data?.baseCurrency ?? me?.baseCurrency ?? 'CHF'
  const available = data.data?.availableYears ?? [year]

  return (
    <>
      <PageHeader
        title={t('cashflow.title')}
        subtitle={t('cashflow.convertedTo', { currency })}
        actions={
          <>
            <Segmented label={t('cashflow.view')} value={view} onChange={setView}
              options={[{ value: 'MONTH', label: t('cashflow.byMonth') }, { value: 'YEAR', label: t('cashflow.byYear') }]} />
            {view === 'MONTH' && (
              <select className="input w-auto" aria-label={t('cashflow.year')} value={year} onChange={(e) => setYear(Number(e.target.value))}>
                {available.map((y) => <option key={y} value={y}>{y}</option>)}
              </select>
            )}
          </>
        }
      />

      {view === 'MONTH' ? (
        data.isPending ? <Spinner /> : data.data && (
          <>
            <MissingRatesNotice currencies={data.data.unconvertedCurrencies} baseCurrency={currency} />
            <div className="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-4">
              <StatTile label={t('cashflow.incomeYear', { year })} value={money(data.data.totals.income, currency, 0)} />
              <StatTile label={t('cashflow.expenseYear', { year })} value={money(data.data.totals.expense, currency, 0)} />
              <StatTile label={t('cashflow.net')} value={money(data.data.totals.net, currency, 0)} tone={data.data.totals.net >= 0 ? 'good' : 'bad'}
                sub={data.data.totals.net >= 0 ? t('cashflow.surplus') : t('cashflow.deficit')} />
              <StatTile label={t('cashflow.savingsRate')} value={percent(data.data.totals.savingsRate)} />
            </div>

            <Card title={t('cashflow.monthlyTrend', { year })} className="mb-4">
              <CashflowChart currency={currency} data={data.data.months.map((m) => ({
                label: monthShort(m.month - 1), title: `${monthName(m.month - 1)} ${year}`, ...m.totals,
              }))} />
            </Card>

            <div className="mb-4 grid gap-4 lg:grid-cols-2">
              <Card title={t('cashflow.expenseByCategory')}>
                <RankedBars currency={currency} emptyText={t('cashflow.noExpenses')}
                  rows={data.data.categories.filter((c) => c.kind === 'EXPENSE').map((c) => ({
                    key: String(c.categoryId), label: c.name, swatch: c.color, value: c.amount, share: c.share,
                  }))} />
              </Card>
              <Card title={t('cashflow.incomeByCategory')}>
                <RankedBars currency={currency} emptyText={t('cashflow.noIncome')}
                  rows={data.data.categories.filter((c) => c.kind === 'INCOME').map((c) => ({
                    key: String(c.categoryId), label: c.name, swatch: c.color, value: c.amount, share: c.share,
                  }))} />
              </Card>
            </div>

            <Card title={t('cashflow.monthlyTable')}>
              <TotalsTable currency={currency} firstHeader={t('cashflow.month')}
                rows={data.data.months.map((m) => ({ key: m.month, label: monthName(m.month - 1), totals: m.totals }))}
                footer={{ label: t('cashflow.totalYear', { year }), totals: data.data.totals }} />
            </Card>
          </>
        )
      ) : years.isPending ? <Spinner /> : years.data && (
        <>
          <MissingRatesNotice currencies={years.data.unconvertedCurrencies} baseCurrency={currency} />
          <Card title={t('cashflow.yearlyComparison')} className="mb-4">
            <CashflowChart currency={currency} data={years.data.years.map((y) => ({ label: String(y.year), title: String(y.year), ...y.totals }))} />
          </Card>
          <Card title={t('cashflow.yearlyTable')}>
            <TotalsTable currency={currency} firstHeader={t('cashflow.year')}
              rows={years.data.years.map((y) => ({ key: y.year, label: String(y.year), totals: y.totals }))} />
          </Card>
        </>
      )}
    </>
  )
}

function TotalsTable({ rows, footer, currency, firstHeader }: {
  rows: { key: number; label: string; totals: CashflowTotals }[]
  footer?: { label: string; totals: CashflowTotals }
  currency: string
  firstHeader: string
}) {
  const { t } = useI18n()
  const cells = (totals: CashflowTotals) => (
    <>
      <td className="tabular px-2 py-2 text-right">{money(totals.income, currency)}</td>
      <td className="tabular px-2 py-2 text-right">{money(totals.expense, currency)}</td>
      <td className={`tabular px-2 py-2 text-right ${totals.net < 0 ? 'text-bad' : ''}`}>{money(totals.net, currency)}</td>
      <td className="tabular px-2 py-2 text-right text-ink-2">{percent(totals.savingsRate)}</td>
    </>
  )
  return (
    <div className="-mx-4 overflow-x-auto sm:mx-0">
      <table className="w-full min-w-[32rem] text-sm">
        <thead>
          <tr className="border-b border-line text-left text-xs text-muted">
            <th className="px-4 py-2 font-medium sm:px-2">{firstHeader}</th>
            <th className="px-2 py-2 text-right font-medium">{t('cashflow.colIncome')}</th>
            <th className="px-2 py-2 text-right font-medium">{t('cashflow.colExpense')}</th>
            <th className="px-2 py-2 text-right font-medium">{t('cashflow.colNet')}</th>
            <th className="px-2 py-2 text-right font-medium">{t('cashflow.colSavings')}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.key} className="border-b border-line last:border-0">
              <td className="px-4 py-2 capitalize sm:px-2">{r.label}</td>
              {cells(r.totals)}
            </tr>
          ))}
        </tbody>
        {footer && (
          <tfoot>
            <tr className="border-t-2 border-line font-semibold">
              <td className="px-4 py-2 sm:px-2">{footer.label}</td>
              {cells(footer.totals)}
            </tr>
          </tfoot>
        )}
      </table>
    </div>
  )
}
