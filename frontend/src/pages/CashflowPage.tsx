import { useState } from 'react'
import { useCashflowYear, useCashflowYears, useMe } from '../api/hooks'
import { Card, MissingRatesNotice, PageHeader, Segmented, Spinner, StatTile } from '../components/ui'
import { CashflowChart } from '../charts/CashflowChart'
import { RankedBars } from '../charts/ChartParts'
import { MONTHS, MONTHS_SHORT, money, percent } from '../lib/format'
import type { CashflowTotals } from '../api/types'

export function CashflowPage() {
  const [year, setYear] = useState(new Date().getFullYear())
  const [view, setView] = useState<'MONTH' | 'YEAR'>('MONTH')
  const me = useMe().data
  const data = useCashflowYear(year)
  const years = useCashflowYears()
  const currency = data.data?.baseCurrency ?? me?.baseCurrency ?? 'CHF'
  const available = data.data?.availableYears ?? [year]

  return (
    <>
      <PageHeader
        title="Entrate e uscite"
        subtitle={`Importi convertiti in ${currency}`}
        actions={
          <>
            <Segmented label="Vista" value={view} onChange={setView}
              options={[{ value: 'MONTH', label: 'Per mese' }, { value: 'YEAR', label: 'Per anno' }]} />
            {view === 'MONTH' && (
              <select className="input w-auto" aria-label="Anno" value={year} onChange={(e) => setYear(Number(e.target.value))}>
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
              <StatTile label={`Entrate ${year}`} value={money(data.data.totals.income, currency, 0)} />
              <StatTile label={`Uscite ${year}`} value={money(data.data.totals.expense, currency, 0)} />
              <StatTile label="Risultato" value={money(data.data.totals.net, currency, 0)} tone={data.data.totals.net >= 0 ? 'good' : 'bad'}
                sub={data.data.totals.net >= 0 ? 'Avanzo' : 'Disavanzo'} />
              <StatTile label="Tasso di risparmio" value={percent(data.data.totals.savingsRate)} />
            </div>

            <Card title={`Andamento mensile ${year}`} className="mb-4">
              <CashflowChart currency={currency} data={data.data.months.map((m) => ({
                label: MONTHS_SHORT[m.month - 1], title: `${MONTHS[m.month - 1]} ${year}`, ...m.totals,
              }))} />
            </Card>

            <div className="mb-4 grid gap-4 lg:grid-cols-2">
              <Card title="Uscite per categoria">
                <RankedBars currency={currency} emptyText="Nessuna uscita"
                  rows={data.data.categories.filter((c) => c.kind === 'EXPENSE').map((c) => ({
                    key: String(c.categoryId), label: c.name, swatch: c.color, value: c.amount, share: c.share,
                  }))} />
              </Card>
              <Card title="Entrate per categoria">
                <RankedBars currency={currency} emptyText="Nessuna entrata"
                  rows={data.data.categories.filter((c) => c.kind === 'INCOME').map((c) => ({
                    key: String(c.categoryId), label: c.name, swatch: c.color, value: c.amount, share: c.share,
                  }))} />
              </Card>
            </div>

            <Card title="Tabella mensile">
              <TotalsTable currency={currency} firstHeader="Mese"
                rows={data.data.months.map((m) => ({ key: m.month, label: MONTHS[m.month - 1], totals: m.totals }))}
                footer={{ label: `Totale ${year}`, totals: data.data.totals }} />
            </Card>
          </>
        )
      ) : years.isPending ? <Spinner /> : years.data && (
        <>
          <MissingRatesNotice currencies={years.data.unconvertedCurrencies} baseCurrency={currency} />
          <Card title="Confronto annuale" className="mb-4">
            <CashflowChart currency={currency} data={years.data.years.map((y) => ({ label: String(y.year), title: String(y.year), ...y.totals }))} />
          </Card>
          <Card title="Tabella annuale">
            <TotalsTable currency={currency} firstHeader="Anno"
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
  const cells = (t: CashflowTotals) => (
    <>
      <td className="tabular px-2 py-2 text-right">{money(t.income, currency)}</td>
      <td className="tabular px-2 py-2 text-right">{money(t.expense, currency)}</td>
      <td className={`tabular px-2 py-2 text-right ${t.net < 0 ? 'text-bad' : ''}`}>{money(t.net, currency)}</td>
      <td className="tabular px-2 py-2 text-right text-ink-2">{percent(t.savingsRate)}</td>
    </>
  )
  return (
    <div className="-mx-4 overflow-x-auto sm:mx-0">
      <table className="w-full min-w-[32rem] text-sm">
        <thead>
          <tr className="border-b border-line text-left text-xs text-muted">
            <th className="px-4 py-2 font-medium sm:px-2">{firstHeader}</th>
            <th className="px-2 py-2 text-right font-medium">Entrate</th>
            <th className="px-2 py-2 text-right font-medium">Uscite</th>
            <th className="px-2 py-2 text-right font-medium">Netto</th>
            <th className="px-2 py-2 text-right font-medium">Risparmio</th>
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
