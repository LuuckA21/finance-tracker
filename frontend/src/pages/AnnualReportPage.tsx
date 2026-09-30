import { useState, type ReactNode } from 'react'
import { Printer } from 'lucide-react'
import { useAnnualReport, useCategories } from '../api/hooks'
import type { AnnualReport, Category } from '../api/types'
import { Badge, Button, Card, EmptyState, MissingRatesNotice, PageHeader, Spinner, StatTile } from '../components/ui'
import { useI18n } from '../i18n'
import { categoryPath } from '../lib/categories'
import { assetClassLabel, change, date, money, percent, signedMoney, signedPercent } from '../lib/format'

/** Stable while categories load. */
const NO_CATEGORIES: Category[] = []

/** One calendar year at a glance: cash flow against the year before, net worth, positions, tags. */
export function AnnualReportPage() {
  const [year, setYear] = useState(() => new Date().getFullYear())
  const report = useAnnualReport(year)
  const { t } = useI18n()
  const data = report.data
  const years = data?.availableYears ?? [year]

  return (
    <>
      <PageHeader
        title={t('report.title', { year })}
        subtitle={data && t('report.period', {
          from: date(`${year}-01-01`), to: date(data.periodEnd), currency: data.baseCurrency,
        })}
        actions={
          <div className="flex flex-wrap items-center gap-2 print:hidden">
            <select className="input w-auto" aria-label={t('report.year')} value={year} onChange={(e) => setYear(Number(e.target.value))}>
              {years.map((y) => <option key={y} value={y}>{y}</option>)}
            </select>
            <Button onClick={() => window.print()}>
              <Printer className="size-4" /> {t('report.print')}
            </Button>
          </div>
        }
      />
      {report.isPending ? <Spinner /> : data && <ReportBody data={data} />}
    </>
  )
}

function ReportBody({ data }: { data: AnnualReport }) {
  const { t } = useI18n()
  const categories = useCategories().data ?? NO_CATEGORIES
  const currency = data.baseCurrency
  const previousYear = data.year - 1
  const partial = data.periodEnd !== `${data.year}-12-31`
  const previousLabel = (amount: string) => partial
    ? t('report.vsSamePeriod', { year: previousYear, amount })
    : t('report.vsYear', { year: previousYear, amount })
  const empty = data.totals.income === 0 && data.totals.expense === 0 && data.totals.transferred === 0
    && data.positions.length === 0
  if (empty) {
    return <Card><EmptyState title={t('report.emptyTitle', { year: data.year })}>{t('report.emptyHelp')}</EmptyState></Card>
  }

  const netWorth = change(data.netWorthEnd, data.netWorthStart)
  const byId = new Map(categories.map((c) => [c.id, c]))
  const categoryName = (id: number | null) => categoryPath(id, byId) || '—'
  const income = data.categories.filter((c) => c.kind === 'INCOME')
  const expense = data.categories.filter((c) => c.kind === 'EXPENSE')
  const startLabel = date(`${previousYear}-12-31`)
  const endLabel = date(data.periodEnd)

  return (
    <>
      <MissingRatesNotice currencies={data.unconvertedCurrencies} baseCurrency={currency} />
      <div className="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-5">
        <StatTile label={t('report.income')} value={money(data.totals.income, currency, 0)}
          sub={<>{t('report.monthlyAverage', { amount: money(data.totals.income / data.months, currency, 0) })}<br />
            {previousLabel(money(data.previousTotals.income, currency, 0))}</>} />
        <StatTile label={t('report.expense')} value={money(data.totals.expense, currency, 0)}
          sub={<>{t('report.monthlyAverage', { amount: money(data.totals.expense / data.months, currency, 0) })}<br />
            {previousLabel(money(data.previousTotals.expense, currency, 0))}</>} />
        <StatTile label={t('report.net')} value={money(data.totals.net, currency, 0)} tone={data.totals.net >= 0 ? 'good' : 'bad'}
          sub={previousLabel(money(data.previousTotals.net, currency, 0))} />
        <StatTile label={t('report.savingsRate')} value={percent(data.totals.savingsRate)}
          sub={previousLabel(percent(data.previousTotals.savingsRate))} />
        <StatTile label={t('report.netWorthEnd')} value={money(data.netWorthEnd, currency, 0)}
          tone={netWorth.amount >= 0 ? 'good' : 'bad'}
          sub={t('report.netWorthChange', { amount: signedMoney(netWorth.amount, currency, 0), percent: signedPercent(netWorth.percent) })} />
      </div>

      <Card title={t('report.categories')} className="mb-4 break-inside-avoid">
        {data.categories.length === 0 ? <p className="text-sm text-muted">{t('report.noCategories')}</p> : (
          <Table headers={[t('entries.category'), String(data.year), String(previousYear), t('report.colChange')]}>
            {[{ label: t('report.income'), rows: income }, { label: t('report.expense'), rows: expense }]
              .filter((group) => group.rows.length > 0)
              .map((group) => [
                <tr key={group.label} className="border-b border-line bg-surface-2 text-xs font-semibold text-ink-2">
                  <td className="px-4 py-1.5 sm:px-2" colSpan={4}>{group.label}</td>
                </tr>,
                ...group.rows.flatMap((c) => [
                  <tr key={c.categoryId} className="border-b border-line last:border-0">
                    <td className="px-4 py-2 sm:px-2">
                      <span className="inline-flex items-center gap-2">
                        <span className="size-2.5 shrink-0 rounded-full" style={{ background: c.color }} aria-hidden />{c.name}
                      </span>
                    </td>
                    <Amount value={money(c.amount, currency)} />
                    <Amount value={money(c.previousAmount, currency)} muted />
                    <Amount value={signedMoney(c.amount - c.previousAmount, currency)} muted />
                  </tr>,
                  // Details under their macro, the macro's own entries named as such
                  ...c.details.map((d) => (
                    <tr key={`${c.categoryId}-${d.categoryId}`} className="border-b border-line text-xs text-ink-2 last:border-0">
                      <td className="py-1.5 pr-2 pl-9 sm:pl-7">
                        {d.categoryId === c.categoryId ? t('categories.withoutDetail', { name: c.name }) : d.name}
                      </td>
                      <Amount value={money(d.amount, currency)} />
                      <Amount value={money(d.previousAmount, currency)} muted />
                      <Amount value={signedMoney(d.amount - d.previousAmount, currency)} muted />
                    </tr>
                  )),
                ]),
              ])}
          </Table>
        )}
        {partial && <p className="mt-3 text-xs text-muted">{t('report.samePeriodNote')}</p>}
      </Card>

      <div className="mb-4 grid gap-4 xl:grid-cols-2 print:grid-cols-1">
        <Card title={t('report.netWorth')} className="min-w-0 break-inside-avoid">
          {data.classes.length === 0 ? <p className="text-sm text-muted">{t('report.noPositions')}</p> : (
            <Table narrow headers={[t('report.colClass'), startLabel, endLabel, t('report.colChange')]}>
              {data.classes.map((c) => (
                <tr key={c.assetClass} className="border-b border-line last:border-0">
                  <td className="px-4 py-2 sm:px-2">{assetClassLabel(c.assetClass)}</td>
                  <Amount value={money(c.start, currency, 0)} muted />
                  <Amount value={money(c.end, currency, 0)} />
                  <Amount value={signedMoney(c.end - c.start, currency, 0)} muted />
                </tr>
              ))}
              <tr className="border-t-2 border-line font-semibold">
                <td className="px-4 py-2 sm:px-2">{t('report.total')}</td>
                <Amount value={money(data.netWorthStart, currency, 0)} />
                <Amount value={money(data.netWorthEnd, currency, 0)} />
                <Amount value={signedMoney(netWorth.amount, currency, 0)} />
              </tr>
            </Table>
          )}
        </Card>

        <Card title={t('report.tags')} className="min-w-0 break-inside-avoid">
          {data.tags.length === 0 ? <p className="text-sm text-muted">{t('report.noTags')}</p> : (
            <Table narrow headers={[t('tags.label'), t('report.colEntries'), t('report.expense'), t('report.income')]}>
              {data.tags.map((tag) => (
                <tr key={tag.tagId} className="border-b border-line last:border-0">
                  <td className="px-4 py-2 sm:px-2">{tag.name}</td>
                  <Amount value={String(tag.entryCount)} muted />
                  <Amount value={money(tag.expense, currency, 0)} />
                  <Amount value={money(tag.income, currency, 0)} muted />
                </tr>
              ))}
            </Table>
          )}
        </Card>
      </div>

      <Card title={t('report.positions')} className="mb-4 break-inside-avoid">
        {data.positions.length === 0 ? <p className="text-sm text-muted">{t('report.noPositions')}</p> : (
          <>
            <Table headers={[t('report.colPosition'), startLabel, endLabel, t('report.colChange'), t('report.colTransfers')]}>
              {data.positions.map((p) => (
                <tr key={p.positionId} className="border-b border-line last:border-0">
                  <td className="px-4 py-2 sm:px-2">
                    {p.name} <span className="text-xs text-muted">{assetClassLabel(p.assetClass)}</span>
                    {p.archived && <> <Badge>{t('positions.archivedBadge')}</Badge></>}
                  </td>
                  <Amount value={money(p.start, currency, 0)} muted />
                  <Amount value={money(p.end, currency, 0)} />
                  <Amount value={p.start === null || p.end === null ? '—' : signedMoney(p.end - p.start, currency, 0)} muted />
                  <Amount value={p.transfersIn === 0 && p.transfersOut === 0 ? '—' : signedMoney(p.transfersIn - p.transfersOut, currency, 0)} />
                </tr>
              ))}
            </Table>
            <p className="mt-3 text-xs text-muted">{t('report.positionsHelp')}</p>
          </>
        )}
      </Card>

      <Card title={t('report.largestExpenses')} className="break-inside-avoid">
        {data.largestExpenses.length === 0 ? <p className="text-sm text-muted">{t('report.noCategories')}</p> : (
          <Table headers={[t('common.date'), t('entries.category'), t('entries.description'), t('common.amount')]}>
            {data.largestExpenses.map((e) => (
              <tr key={e.entryId} className="border-b border-line last:border-0">
                <td className="tabular px-4 py-2 text-ink-2 sm:px-2">{date(e.date)}</td>
                <td className="px-2 py-2">{categoryName(e.categoryId)}</td>
                <td className="px-2 py-2 text-ink-2">{e.description}</td>
                <td className="tabular px-2 py-2 text-right">
                  {money(e.amountBase, currency)}
                  {e.currency !== currency && <p className="text-xs text-muted">{money(e.amount, e.currency)}</p>}
                </td>
              </tr>
            ))}
          </Table>
        )}
      </Card>
    </>
  )
}

/** A table whose first column is text and the others right-aligned numbers. */
function Table({ headers, children, narrow = false }: { headers: string[]; children: ReactNode; narrow?: boolean }) {
  return (
    <div className="relative -mx-4 overflow-x-auto sm:mx-0">
      <table className={`w-full text-sm ${narrow ? 'min-w-[22rem]' : 'min-w-[32rem]'}`}>
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
