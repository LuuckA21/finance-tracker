import { Link } from 'react-router'
import { ArrowRight } from 'lucide-react'
import { useCashflowYear, useMe, useNetWorthDetail, useNetWorthSeries } from '../api/hooks'
import { Card, MissingRatesNotice, PageHeader, Spinner, StatTile } from '../components/ui'
import { CashflowChart } from '../charts/CashflowChart'
import { NetWorthLine } from '../charts/NetWorthCharts'
import { useI18n } from '../i18n'
import { money, monthName, monthShort, monthsAgo, percent, date } from '../lib/format'

export function OverviewPage() {
  const me = useMe().data
  const { t } = useI18n()
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
      <PageHeader title={t('overview.greeting', { name: me?.username ?? '' })} subtitle={t('overview.asOf', { date: date(detail.data?.date) })} />
      <MissingRatesNotice currencies={missing} baseCurrency={currency} />

      <section className="card mb-4 p-5 sm:p-6">
        <p className="text-sm font-medium text-ink-2">{t('overview.netWorth')}</p>
        {detail.isPending ? <Spinner /> : (
          <>
            <p className="mt-1 text-4xl font-semibold tracking-tight sm:text-5xl">{money(detail.data?.total ?? 0, currency, 0)}</p>
            {delta !== null && (
              <p className={`mt-2 text-sm ${delta >= 0 ? 'text-good' : 'text-bad'}`}>
                {delta >= 0 ? '▲' : '▼'} {t('overview.sinceEndOf', { amount: money(Math.abs(delta), currency, 0), month: monthName(new Date(previous!.date).getMonth()) })}
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
          {t('overview.netWorthDetail')} <ArrowRight className="size-4" />
        </Link>
      </section>

      <div className="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatTile label={t('overview.incomeMonth', { month: monthShort(month) })} value={money(thisMonth?.income ?? 0, currency, 0)} />
        <StatTile label={t('overview.expenseMonth', { month: monthShort(month) })} value={money(thisMonth?.expense ?? 0, currency, 0)} />
        <StatTile
          label={t('overview.netMonth', { month: monthShort(month) })}
          value={money(thisMonth?.net ?? 0, currency, 0)}
          tone={(thisMonth?.net ?? 0) >= 0 ? 'good' : 'bad'}
          sub={thisMonth?.savingsRate != null ? t('overview.savingsRate', { rate: percent(thisMonth.savingsRate) }) : undefined}
        />
        <StatTile
          label={t('overview.savingsYear', { year })}
          value={money(ytd?.net ?? 0, currency, 0)}
          sub={ytd?.savingsRate != null ? t('overview.shareOfIncome', { rate: percent(ytd.savingsRate) }) : t('overview.noIncome')}
        />
      </div>

      <Card title={t('overview.cashflowYear', { year })} actions={<Link to="/flussi" className="text-sm text-accent hover:underline">{t('overview.details')}</Link>}>
        {cashflow.isPending ? <Spinner /> : (
          <CashflowChart
            currency={currency}
            data={(cashflow.data?.months ?? []).map((m) => ({
              label: monthShort(m.month - 1),
              title: `${monthName(m.month - 1)} ${year}`,
              ...m.totals,
            }))}
          />
        )}
      </Card>
    </>
  )
}
