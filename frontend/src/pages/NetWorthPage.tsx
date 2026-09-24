import { useState } from 'react'
import { Link } from 'react-router'
import { useMe, useNetWorthDetail, useNetWorthSeries } from '../api/hooks'
import type { AssetClass, Granularity } from '../api/types'
import { Card, EmptyState, MissingRatesNotice, PageHeader, Segmented, Spinner } from '../components/ui'
import { NetWorthLine, NetWorthStacked } from '../charts/NetWorthCharts'
import { RankedBars } from '../charts/ChartParts'
import { assetClassColor, useChartTheme } from '../charts/theme'
import { useI18n } from '../i18n'
import { ASSET_CLASSES, assetClassLabel, date, money, monthsAgo, number, percent, periodLabel, today } from '../lib/format'

type Range = '12' | '36' | 'ALL'

export function NetWorthPage() {
  const me = useMe().data
  const theme = useChartTheme()
  const { t } = useI18n()
  const [granularity, setGranularity] = useState<Granularity>('MONTH')
  const [range, setRange] = useState<Range>('12')
  const [at, setAt] = useState(today())
  const [showTable, setShowTable] = useState(false)

  const from = granularity === 'MONTH' && range !== 'ALL' ? monthsAgo(Number(range) - 1) : undefined
  const series = useNetWorthSeries(granularity, from)
  const detail = useNetWorthDetail(at)
  const currency = detail.data?.baseCurrency ?? me?.baseCurrency ?? 'CHF'
  const points = series.data?.points ?? []
  const missing = [...new Set([...(series.data?.unconvertedCurrencies ?? []), ...(detail.data?.unconvertedCurrencies ?? [])])]

  const byClass = Object.entries(detail.data?.byClass ?? {})
    .map(([c, v]) => ({ c: c as AssetClass, v: v ?? 0 }))
    .filter((x) => x.v !== 0)
    .sort((a, b) => b.v - a.v)

  return (
    <>
      <PageHeader
        title={t('netWorth.title')}
        subtitle={t('netWorth.convertedTo', { currency })}
        actions={
          <>
            <Segmented label={t('netWorth.granularity')} value={granularity} onChange={setGranularity}
              options={[{ value: 'MONTH', label: t('netWorth.monthly') }, { value: 'YEAR', label: t('netWorth.yearly') }]} />
            {granularity === 'MONTH' && (
              <Segmented label={t('netWorth.range')} value={range} onChange={setRange}
                options={[{ value: '12', label: t('netWorth.range12') }, { value: '36', label: t('netWorth.range36') }, { value: 'ALL', label: t('netWorth.rangeAll') }]} />
            )}
          </>
        }
      />
      <MissingRatesNotice currencies={missing} baseCurrency={currency} />

      {series.isPending ? <Spinner /> : points.length === 0 || !series.data?.firstSnapshotDate ? (
        <EmptyState title={t('netWorth.emptyTitle')}>
          {t('netWorth.emptyBefore')} <Link className="text-accent underline" to="/posizioni">{t('netWorth.emptyLink')}</Link> {t('netWorth.emptyAfter')}
        </EmptyState>
      ) : (
        <>
          <div className="mb-4 grid gap-4 lg:grid-cols-2">
            <Card title={t('netWorth.total')}>
              <NetWorthLine points={points} currency={currency} />
            </Card>
            <Card title={t('netWorth.composition')}>
              <NetWorthStacked points={points} currency={currency} height={260} />
            </Card>
          </div>

          <Card className="mb-4" title={t('netWorth.chartData')}
            actions={<button type="button" className="text-sm text-accent hover:underline" aria-expanded={showTable}
              onClick={() => setShowTable((v) => !v)}>{showTable ? t('netWorth.hideTable') : t('netWorth.showTable')}</button>}>
            {showTable && (
              <div className="-mx-4 overflow-x-auto sm:mx-0">
                <table className="w-full min-w-[32rem] text-sm">
                  <thead>
                    <tr className="border-b border-line text-left text-xs text-muted">
                      <th className="px-4 py-2 font-medium sm:px-2">{t('netWorth.period')}</th>
                      {ASSET_CLASSES.filter((c) => points.some((p) => p.byClass[c])).map((c) => (
                        <th key={c} className="px-2 py-2 text-right font-medium">{assetClassLabel(c)}</th>
                      ))}
                      <th className="px-2 py-2 text-right font-medium">{t('common.total')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {[...points].reverse().map((p) => (
                      <tr key={p.period} className="border-b border-line last:border-0">
                        <td className="px-4 py-2 sm:px-2">{periodLabel(p.period)}</td>
                        {ASSET_CLASSES.filter((c) => points.some((q) => q.byClass[c])).map((c) => (
                          <td key={c} className="tabular px-2 py-2 text-right text-ink-2">{money(p.byClass[c] ?? 0, currency, 0)}</td>
                        ))}
                        <td className="tabular px-2 py-2 text-right font-medium">{money(p.total, currency, 0)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>
        </>
      )}

      <div className="grid gap-4 lg:grid-cols-[1fr_2fr]">
        <Card title={t('netWorth.breakdown')}>
          <p className="mb-1 text-xs text-ink-2">{t('netWorth.totalAt', { date: date(detail.data?.date) })}</p>
          <p className="mb-4 text-3xl font-semibold tracking-tight">{money(detail.data?.total ?? 0, currency, 0)}</p>
          <RankedBars currency={currency} emptyText={t('netWorth.noValuedPositions')}
            rows={byClass.map(({ c, v }) => ({
              key: c, label: assetClassLabel(c), swatch: assetClassColor(theme, c), value: v,
              share: detail.data && detail.data.total ? (v / detail.data.total) * 100 : null,
            }))} />
        </Card>

        <Card title={t('netWorth.positions')} actions={
          <label className="flex items-center gap-2 whitespace-nowrap text-xs text-ink-2">
            {t('netWorth.valueAt')}
            <input type="date" className="input w-auto py-1" value={at} max={today()} onChange={(e) => setAt(e.target.value || today())} />
          </label>
        }>
          {detail.isPending ? <Spinner /> : (detail.data?.positions.length ?? 0) === 0 ? (
            <EmptyState title={t('netWorth.noPositionsAtDate')} />
          ) : (
            <div className="-mx-4 overflow-x-auto sm:mx-0">
              <table className="w-full min-w-[36rem] text-sm">
                <thead>
                  <tr className="border-b border-line text-left text-xs text-muted">
                    <th className="px-4 py-2 font-medium sm:px-2">{t('netWorth.colPosition')}</th>
                    <th className="px-2 py-2 text-right font-medium">{t('netWorth.colQuantityPrice')}</th>
                    <th className="px-2 py-2 text-right font-medium">{t('netWorth.colValue')}</th>
                    <th className="px-2 py-2 text-right font-medium">{t('netWorth.colShare')}</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.data!.positions.map((p) => (
                    <tr key={p.positionId} className="border-b border-line last:border-0">
                      <td className="px-4 py-2 sm:px-2">
                        <Link to={`/posizioni/${p.positionId}`} className="flex items-center gap-2 hover:underline">
                          <span className="size-2.5 shrink-0 rounded-full" style={{ background: assetClassColor(theme, p.assetClass) }} aria-hidden />
                          <span className="font-medium">{p.name}</span>
                          {p.symbol && <span className="text-xs text-muted">{p.symbol}</span>}
                        </Link>
                        <span className="ml-4.5 text-xs text-muted">{assetClassLabel(p.assetClass)} · {t('netWorth.updated', { date: date(p.asOf) })}</span>
                      </td>
                      <td className="tabular px-2 py-2 text-right text-ink-2">
                        {p.assetClass === 'CASH' ? money(p.valueLocal, p.currency) : `${number(p.quantity)} × ${money(p.unitPrice, p.currency)}`}
                      </td>
                      <td className="tabular px-2 py-2 text-right font-medium">
                        {p.valueBase === null ? <span className="text-warn-ink">{money(p.valueLocal, p.currency)}</span> : money(p.valueBase, currency)}
                      </td>
                      <td className="tabular px-2 py-2 text-right text-ink-2">{percent(p.share)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>
    </>
  )
}
