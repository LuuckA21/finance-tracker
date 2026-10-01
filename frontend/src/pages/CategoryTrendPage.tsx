import { useState, type ReactNode } from 'react'
import { ArrowLeft } from 'lucide-react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { ApiError } from '../api/client'
import { useCategories, useCategoryTrend } from '../api/hooks'
import type { CategoryTrend } from '../api/types'
import { CategoryTrendChart } from '../charts/CategoryTrendChart'
import { CategoryOptions } from '../components/CategoryOptions'
import { Card, EmptyState, MissingRatesNotice, PageHeader, Spinner, StatTile } from '../components/ui'
import { useI18n } from '../i18n'
import { barParts, categoryPath, type BarPart } from '../lib/categories'
import { change, money, monthName, signedMoney, signedPercent } from '../lib/format'
import { MAX_PARTS } from '../charts/CategoryBars'

/** One category (a macro with its details) month by month in a year, against the year before. */
export function CategoryTrendPage() {
  const { t } = useI18n()
  const navigate = useNavigate()
  const id = Number(useParams().id)
  const [params] = useSearchParams()
  const [thisYear] = useState(() => new Date().getFullYear())
  const year = Number(params.get('anno')) || thisYear
  const categories = useCategories().data ?? []
  const trend = useCategoryTrend(id, year)
  const byId = new Map(categories.map((c) => [c.id, c]))
  const go = (categoryId: number, toYear: number) => navigate(`/flussi/categoria/${categoryId}?anno=${toYear}`)

  const back = (
    <Link to="/flussi" className="mb-3 inline-flex items-center gap-1 text-sm text-ink-2 hover:text-ink">
      <ArrowLeft className="size-4" /> {t('cashflow.title')}
    </Link>
  )
  if (trend.isError) {
    const missing = trend.error instanceof ApiError && trend.error.status === 404
    return <>{back}<EmptyState title={missing ? t('trend.notFound') : t('trend.loadError')}>{null}</EmptyState></>
  }
  if (trend.isPending) return <Spinner />
  const data = trend.data
  const years = data.availableYears.includes(year) ? data.availableYears : [...data.availableYears, year].toSorted()

  return (
    <>
      {back}
      <PageHeader
        title={t('trend.title', { name: categoryPath(id, byId) || data.name })}
        subtitle={t('cashflow.convertedTo', { currency: data.baseCurrency })}
        actions={
          <>
            <select className="input w-auto max-w-[16rem]" aria-label={t('entries.category')} value={id}
              onChange={(e) => go(Number(e.target.value), year)}>
              <CategoryOptions categories={categories} />
            </select>
            <select className="input w-auto" aria-label={t('cashflow.year')} value={year} onChange={(e) => go(id, Number(e.target.value))}>
              {years.map((y) => <option key={y} value={y}>{y}</option>)}
            </select>
          </>
        }
      />
      <MissingRatesNotice currencies={data.unconvertedCurrencies} baseCurrency={data.baseCurrency} />
      {data.total === 0 && data.previousTotal === 0 ? (
        <Card><EmptyState title={t('trend.emptyTitle', { year })}>{t('trend.emptyHelp')}</EmptyState></Card>
      ) : <TrendBody data={data} />}
    </>
  )
}

function TrendBody({ data }: { data: CategoryTrend }) {
  const { t } = useI18n()
  const currency = data.baseCurrency
  const partial = data.lastMonth > 0 && data.lastMonth < 12
  const toDate = change(data.toDate, data.previousToDate)
  // Over the months already over: the current one would weigh as if it were complete
  const average = data.completedMonths > 0
    ? data.months.slice(0, data.completedMonths).reduce((sum, m) => sum + m.amount, 0) / data.completedMonths : null
  const parts = barParts(data.categoryId, data.details.map((d) => ({ ...d, share: null })), MAX_PARTS)
  const partName = (part: BarPart) => part.role === 'own' ? t('categories.withoutDetail', { name: data.name })
    : part.role === 'rest' ? t('cashflow.otherDetails', { count: part.count }) : part.name
  const detailName = (categoryId: number, name: string) =>
    categoryId === data.categoryId && parts.length > 0 ? t('categories.withoutDetail', { name: data.name }) : name

  return (
    <>
      <div className="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatTile label={partial ? t('trend.toDate', { year: data.year }) : t('trend.total', { year: data.year })}
          value={money(data.toDate, currency, 0)} />
        <StatTile label={partial ? t('trend.samePeriod', { year: data.year - 1 }) : t('trend.total', { year: data.year - 1 })}
          value={money(data.previousToDate, currency, 0)}
          sub={t('trend.change', { amount: signedMoney(toDate.amount, currency, 0), percent: signedPercent(toDate.percent) })} />
        <StatTile label={t('trend.monthlyAverage')} value={average === null ? '—' : money(average, currency, 0)}
          sub={average === null ? undefined : t('trend.monthlyAverageHint', { months: data.completedMonths })} />
        <StatTile label={t('trend.total', { year: data.year - 1 })} value={money(data.previousTotal, currency, 0)} />
      </div>

      <Card title={t('trend.monthly', { year: data.year })} className="mb-4">
        <CategoryTrendChart data={data} partName={partName} />
      </Card>

      <div className="grid gap-4 xl:grid-cols-2">
        <Card title={t('trend.table')} className="min-w-0">
          <Table headers={[t('cashflow.month'), String(data.year), String(data.year - 1), t('report.colChange')]}>
            {data.months.map((m) => {
              const future = m.month > data.lastMonth
              return (
                <tr key={m.month} className="border-b border-line last:border-0">
                  <td className="px-4 py-2 capitalize sm:px-2">{monthName(m.month - 1)}</td>
                  <Amount value={future ? '—' : money(m.amount, currency)} muted={future} />
                  <Amount value={money(m.previous, currency)} muted />
                  <Amount value={future ? '—' : signedMoney(m.amount - m.previous, currency)} muted />
                </tr>
              )
            })}
            <tr className="border-t-2 border-line font-medium">
              <td className="px-4 py-2 sm:px-2">{t('common.total')}</td>
              <Amount value={money(data.total, currency)} />
              <Amount value={money(data.previousTotal, currency)} />
              <Amount value={signedMoney(data.total - data.previousTotal, currency)} />
            </tr>
          </Table>
          {partial && <p className="mt-3 text-xs text-muted">{t('trend.partialNote')}</p>}
        </Card>
        {data.details.length > 1 && (
          <Card title={t('trend.details')} className="min-w-0">
            <Table headers={[t('trend.detail'), String(data.year), String(data.year - 1), t('report.colChange')]}>
              {data.details.map((d) => (
                <tr key={d.categoryId} className="border-b border-line last:border-0">
                  <td className="px-4 py-2 sm:px-2">
                    {d.categoryId === data.categoryId ? detailName(d.categoryId, d.name) : (
                      <Link to={`/flussi/categoria/${d.categoryId}?anno=${data.year}`} className="hover:underline">{d.name}</Link>
                    )}
                  </td>
                  <Amount value={money(d.amount, currency)} />
                  <Amount value={money(d.previous, currency)} muted />
                  <Amount value={signedMoney(d.amount - d.previous, currency)} muted />
                </tr>
              ))}
            </Table>
          </Card>
        )}
      </div>
    </>
  )
}

function Table({ headers, children }: { headers: string[]; children: ReactNode }) {
  return (
    <div className="relative -mx-4 overflow-x-auto sm:mx-0">
      <table className="w-full min-w-[26rem] text-sm">
        <thead>
          <tr className="border-b border-line text-left text-xs text-muted">
            {headers.map((h, i) => (
              <th key={h + i} className={`py-2 font-medium ${i === 0 ? 'px-4 sm:px-2' : 'px-2 text-right'}`}>{h}</th>
            ))}
          </tr>
        </thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  )
}

function Amount({ value, muted = false }: { value: string; muted?: boolean }) {
  return <td className={`tabular px-2 py-2 text-right ${muted ? 'text-ink-2' : ''}`}>{value}</td>
}
