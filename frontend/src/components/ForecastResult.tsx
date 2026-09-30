import { useState } from 'react'
import type { CategoryKind, Forecast } from '../api/types'
import { CashflowChart } from '../charts/CashflowChart'
import { useI18n } from '../i18n'
import { money, monthName, monthShort, percent, signedMoney, signedPercent } from '../lib/format'
import { Card, MissingRatesNotice, Segmented, StatTile } from './ui'

/** Change over the base in percent; null without a base to compare with. */
function change(forecast: number, base: number): number | null {
  return base !== 0 ? ((forecast - base) / Math.abs(base)) * 100 : null
}

/** The forecast of a scenario: totals against the base, where the difference comes from, month by month, by category. */
export function ForecastResult({ forecast, currency }: { forecast: Forecast; currency: string }) {
  const { t } = useI18n()
  const [kind, setKind] = useState<CategoryKind>('EXPENSE')
  const { totals, baseTotals, year } = forecast
  const vsBase = (value: number, base: number) => {
    const pct = change(value, base)
    return t('forecast.vsBase', { amount: money(base, currency, 0) }) + (pct === null ? '' : ` · ${signedPercent(pct)}`)
  }
  // Savings from January to each month
  const cumulative = forecast.months.reduce<number[]>((acc, m) => [...acc, (acc.at(-1) ?? 0) + m.forecast.net], [])
  const rows: { label: string; key: 'income' | 'expense' | 'net' }[] = [
    { label: t('cashflow.colIncome'), key: 'income' },
    { label: t('cashflow.colExpense'), key: 'expense' },
    { label: t('cashflow.net'), key: 'net' },
  ]
  const cell = 'tabular whitespace-nowrap px-2 py-2 text-right'

  return (
    <>
      <MissingRatesNotice currencies={forecast.unconvertedCurrencies} baseCurrency={currency} />
      <div className="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatTile label={t('cashflow.incomeYear', { year })} value={money(totals.income, currency, 0)} sub={vsBase(totals.income, baseTotals.income)} />
        <StatTile label={t('cashflow.expenseYear', { year })} value={money(totals.expense, currency, 0)} sub={vsBase(totals.expense, baseTotals.expense)} />
        <StatTile label={t('cashflow.net')} value={money(totals.net, currency, 0)} tone={totals.net >= 0 ? 'good' : 'bad'}
          sub={totals.net >= 0 ? t('cashflow.surplus') : t('cashflow.deficit')} />
        <StatTile label={t('cashflow.savingsRate')} value={percent(totals.savingsRate)}
          sub={t('forecast.vsBase', { amount: percent(baseTotals.savingsRate) })} />
      </div>

      <Card title={t('forecast.comparison')} className="mb-4">
        <div className="relative -mx-4 overflow-x-auto sm:mx-0">
          <table className="w-full min-w-[32rem] text-sm">
            <thead>
              <tr className="border-b border-line text-xs text-muted">
                <th scope="col" className="px-4 py-2 text-left font-medium sm:px-2"><span className="sr-only">{t('cashflow.matrixKind')}</span></th>
                <th scope="col" className="px-2 py-2 text-right font-medium">{t('forecast.colBase')}</th>
                <th scope="col" className="px-2 py-2 text-right font-medium">{t('forecast.colGrowth')}</th>
                <th scope="col" className="px-2 py-2 text-right font-medium">{t('forecast.colItems')}</th>
                <th scope="col" className="px-2 py-2 text-right font-medium">{t('forecast.colForecast')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.key} className={`border-b border-line last:border-0 ${r.key === 'net' ? 'font-semibold' : ''}`}>
                  <th scope="row" className="px-4 py-2 text-left font-medium sm:px-2">{r.label}</th>
                  <td className={cell}>{money(baseTotals[r.key], currency, 0)}</td>
                  <td className={cell}>{signedMoney(forecast.fromGrowth[r.key], currency, 0)}</td>
                  <td className={cell}>{signedMoney(forecast.fromItems[r.key], currency, 0)}</td>
                  <td className={cell}>{money(totals[r.key], currency, 0)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      <Card title={t('forecast.monthlyTrend', { year })} className="mb-4">
        <CashflowChart currency={currency} data={forecast.months.map((m) => ({
          label: monthShort(m.month - 1), title: `${monthName(m.month - 1)} ${year}`, ...m.forecast,
        }))} />
      </Card>

      <Card title={t('forecast.monthly')} className="mb-4">
        <div className="relative -mx-4 overflow-x-auto sm:mx-0">
          <table className="w-full min-w-[40rem] text-sm">
            <thead>
              <tr className="border-b border-line text-left text-xs text-muted">
                <th scope="col" className="px-4 py-2 font-medium sm:px-2">{t('cashflow.month')}</th>
                <th scope="col" className="px-2 py-2 text-right font-medium">{t('cashflow.colIncome')}</th>
                <th scope="col" className="px-2 py-2 text-right font-medium">{t('cashflow.colExpense')}</th>
                <th scope="col" className="px-2 py-2 text-right font-medium">{t('cashflow.colNet')}</th>
                <th scope="col" className="px-2 py-2 text-right font-medium">{t('forecast.cumulative')}</th>
                <th scope="col" className="px-2 py-2 text-right font-medium">{t('forecast.baseNet')}</th>
              </tr>
            </thead>
            <tbody>
              {forecast.months.map((m, i) => (
                  <tr key={m.month} className="border-b border-line last:border-0">
                    <td className="px-4 py-2 capitalize sm:px-2">{monthName(m.month - 1)}</td>
                    <td className={cell}>{money(m.forecast.income, currency)}</td>
                    <td className={cell}>{money(m.forecast.expense, currency)}</td>
                    <td className={`${cell} ${m.forecast.net < 0 ? 'text-bad' : ''}`}>{money(m.forecast.net, currency)}</td>
                    <td className={`${cell} ${cumulative[i] < 0 ? 'text-bad' : ''}`}>{money(cumulative[i], currency)}</td>
                    <td className={`${cell} text-ink-2`}>{money(m.base.net, currency)}</td>
                  </tr>
              ))}
            </tbody>
            <tfoot>
              <tr className="border-t-2 border-line font-semibold">
                <td className="px-4 py-2 sm:px-2">{t('cashflow.totalYear', { year })}</td>
                <td className={cell}>{money(totals.income, currency)}</td>
                <td className={cell}>{money(totals.expense, currency)}</td>
                <td className={cell}>{money(totals.net, currency)}</td>
                <td className={cell}>{money(totals.net, currency)}</td>
                <td className={`${cell} text-ink-2`}>{money(baseTotals.net, currency)}</td>
              </tr>
            </tfoot>
          </table>
        </div>
      </Card>

      <Card title={t('forecast.byCategory')} className="mb-4"
        actions={<Segmented label={t('cashflow.matrixKind')} value={kind} onChange={setKind}
          options={[{ value: 'EXPENSE', label: t('cashflow.colExpense') }, { value: 'INCOME', label: t('cashflow.colIncome') }]} />}>
        <div className="relative -mx-4 overflow-x-auto sm:mx-0">
          <table className="w-full min-w-[28rem] text-sm">
            <thead>
              <tr className="border-b border-line text-left text-xs text-muted">
                <th scope="col" className="px-4 py-2 font-medium sm:px-2">{t('cashflow.colCategory')}</th>
                <th scope="col" className="px-2 py-2 text-right font-medium">{t('forecast.colBase')}</th>
                <th scope="col" className="px-2 py-2 text-right font-medium">{t('forecast.colForecast')}</th>
                <th scope="col" className="px-2 py-2 text-right font-medium">{t('forecast.colChange')}</th>
              </tr>
            </thead>
            <tbody>
              {forecast.categories.filter((c) => c.kind === kind).map((c) => (
                <tr key={c.categoryId} className="border-b border-line last:border-0">
                  <th scope="row" className="px-4 py-2 text-left font-normal sm:px-2">
                    <span className="flex items-center gap-2">
                      <span className="size-2.5 shrink-0 rounded-full" style={{ background: c.color }} aria-hidden />
                      {c.name}
                    </span>
                  </th>
                  <td className={`${cell} text-ink-2`}>{money(c.base, currency, 0)}</td>
                  <td className={cell}>{money(c.forecast, currency, 0)}</td>
                  <td className={cell}>{signedMoney(c.forecast - c.base, currency, 0)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="mt-3 text-xs text-muted">{t('forecast.byCategoryHelp')}</p>
      </Card>
    </>
  )
}
