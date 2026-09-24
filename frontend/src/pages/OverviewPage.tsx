import { Link } from 'react-router'
import { ArrowRight } from 'lucide-react'
import { useCashflowYear, useMe, useNetWorthDetail, useNetWorthSeries } from '../api/hooks'
import { Card, MissingRatesNotice, PageHeader, Spinner, StatTile } from '../components/ui'
import { CashflowChart } from '../charts/CashflowChart'
import { NetWorthLine } from '../charts/NetWorthCharts'
import { MONTHS, MONTHS_SHORT, money, monthsAgo, percent, date } from '../lib/format'

export function OverviewPage() {
  const me = useMe().data
  const now = new Date()
  const year = now.getFullYear()
  const month = now.getMonth()

  const detail = useNetWorthDetail()
  const series = useNetWorthSeries('MONTH', monthsAgo(12))
  const cashflow = useCashflowYear(year)

  const currency = detail.data?.baseCurrency ?? me?.baseCurrency ?? 'CHF'
  const points = series.data?.points ?? []
  const previous = points.length >= 2 ? points[points.length - 2] : null
  const delta = detail.data && previous ? detail.data.total - previous.total : null

  const thisMonth = cashflow.data?.months[month]?.totals
  const ytd = cashflow.data?.totals
  const missing = [...new Set([...(detail.data?.unconvertedCurrencies ?? []), ...(cashflow.data?.unconvertedCurrencies ?? [])])]

  return (
    <>
      <PageHeader title={`Ciao ${me?.username ?? ''}`} subtitle={`Situazione al ${date(detail.data?.date)}`} />
      <MissingRatesNotice currencies={missing} baseCurrency={currency} />

      <section className="card mb-4 p-5 sm:p-6">
        <p className="text-sm font-medium text-ink-2">Patrimonio netto</p>
        {detail.isPending ? <Spinner /> : (
          <>
            <p className="mt-1 text-4xl font-semibold tracking-tight sm:text-5xl">{money(detail.data?.total ?? 0, currency, 0)}</p>
            {delta !== null && (
              <p className={`mt-2 text-sm ${delta >= 0 ? 'text-good' : 'text-bad'}`}>
                {delta >= 0 ? '▲' : '▼'} {money(Math.abs(delta), currency, 0)} rispetto a fine {MONTHS[new Date(previous!.date).getMonth()]}
              </p>
            )}
          </>
        )}
        {points.length > 1 && (
          <div className="mt-4">
            <NetWorthLine points={points} currency={currency} height={180} />
          </div>
        )}
        <Link to="/patrimonio" className="mt-3 inline-flex items-center gap-1 text-sm text-accent hover:underline">
          Dettaglio patrimonio <ArrowRight className="size-4" />
        </Link>
      </section>

      <div className="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatTile label={`Entrate ${MONTHS_SHORT[month]}`} value={money(thisMonth?.income ?? 0, currency, 0)} />
        <StatTile label={`Uscite ${MONTHS_SHORT[month]}`} value={money(thisMonth?.expense ?? 0, currency, 0)} />
        <StatTile
          label={`Risultato ${MONTHS_SHORT[month]}`}
          value={money(thisMonth?.net ?? 0, currency, 0)}
          tone={(thisMonth?.net ?? 0) >= 0 ? 'good' : 'bad'}
          sub={thisMonth?.savingsRate != null ? `Tasso di risparmio ${percent(thisMonth.savingsRate)}` : undefined}
        />
        <StatTile
          label={`Risparmio ${year}`}
          value={money(ytd?.net ?? 0, currency, 0)}
          sub={ytd?.savingsRate != null ? `${percent(ytd.savingsRate)} delle entrate` : 'Nessuna entrata registrata'}
        />
      </div>

      <Card title={`Entrate e uscite ${year}`} actions={<Link to="/flussi" className="text-sm text-accent hover:underline">Dettaglio</Link>}>
        {cashflow.isPending ? <Spinner /> : (
          <CashflowChart
            currency={currency}
            data={(cashflow.data?.months ?? []).map((m) => ({
              label: MONTHS_SHORT[m.month - 1],
              title: `${MONTHS[m.month - 1]} ${year}`,
              ...m.totals,
            }))}
          />
        )}
      </Card>
    </>
  )
}
