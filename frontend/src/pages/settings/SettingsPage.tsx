import { useState, type FormEvent } from 'react'
import { NavLink, useParams } from 'react-router'
import { Pencil, RefreshCw, Trash2 } from 'lucide-react'
import { errorMessage } from '../../api/client'
import {
  useCategories,
  useCentralRates,
  useDeleteCategory,
  useDeleteFxRate,
  useEcbStatus,
  useFxRates,
  useLoginHistory,
  useMe,
  useRefreshEcb,
  useSaveCategory,
  useSaveFxRate,
  useUpdateSettings,
} from '../../api/hooks'
import type { Category, EntryKind } from '../../api/types'
import { Badge, Button, Card, EmptyState, ErrorAlert, Field, Modal, PageHeader, Segmented, Spinner } from '../../components/ui'
import { LANGUAGES, LANGUAGE_NAMES, useI18n, type Language, type MessageKey } from '../../i18n'
import { applyPreferences } from '../../preferences'
import { THEMES, type Theme } from '../../preferences/theme'
import { COMMON_CURRENCIES, date, dateTime, number, parseDecimal, today } from '../../lib/format'
import { ChangePasswordForm } from './ChangePasswordForm'
import { MfaSection } from './MfaSection'

const TABS = [
  { id: 'account', label: 'settings.tabAccount' },
  { id: 'categorie', label: 'settings.tabCategories' },
  { id: 'cambi', label: 'settings.tabFx' },
] as const

export function SettingsPage() {
  const tab = useParams().tab ?? 'account'
  const { t } = useI18n()
  return (
    <>
      <PageHeader title={t('settings.title')} />
      <nav className="mb-5 flex gap-1 overflow-x-auto border-b border-line" aria-label={t('settings.sections')}>
        {TABS.map((item) => (
          <NavLink key={item.id} to={`/impostazioni/${item.id}`}
            className={() => `whitespace-nowrap border-b-2 px-3 py-2 text-sm ${tab === item.id ? 'border-accent font-medium text-ink' : 'border-transparent text-ink-2 hover:text-ink'}`}>
            {t(item.label)}
          </NavLink>
        ))}
      </nav>
      {tab === 'categorie' ? <CategoriesTab /> : tab === 'cambi' ? <FxTab /> : <AccountTab />}
    </>
  )
}

// ------------------------------------------------------------------ account

function AccountTab() {
  const { t } = useI18n()
  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
      <Card title={t('settings.preferences')}><PreferencesForm /></Card>
      <Card title={t('settings.baseCurrency')}><BaseCurrencyForm /></Card>
      <Card title={t('settings.twoFactor')}><MfaSection /></Card>
      <Card title={t('settings.changePassword')}><ChangePasswordForm /></Card>
      <Card title={t('settings.recentLogins')}><LoginHistory /></Card>
    </div>
  )
}

/** Applied at once, then saved to the profile; a failed save puts the previous values back. */
function PreferencesForm() {
  const me = useMe().data
  const update = useUpdateSettings()
  const { t } = useI18n()
  const [error, setError] = useState<string | null>(null)
  if (!me) return null

  async function change(prefs: { language?: Language; theme?: Theme }) {
    if (!me) return
    const previous = { language: me.language, theme: me.theme }
    setError(null)
    applyPreferences({ ...previous, ...prefs })
    try {
      await update.mutateAsync(prefs)
    } catch (err) {
      applyPreferences(previous)
      setError(errorMessage(err))
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm text-ink-2">{t('settings.preferencesHelp')}</p>
      <div className="flex flex-col gap-1">
        <span className="text-xs font-medium text-ink-2">{t('settings.language')}</span>
        <Segmented label={t('settings.language')} value={me.language} onChange={(language) => change({ language })}
          options={LANGUAGES.map((l) => ({ value: l, label: LANGUAGE_NAMES[l] }))} />
      </div>
      <div className="flex flex-col gap-1">
        <span className="text-xs font-medium text-ink-2">{t('settings.theme')}</span>
        <Segmented label={t('settings.theme')} value={me.theme} onChange={(theme) => change({ theme })}
          options={THEMES.map((th) => ({ value: th, label: t(`settings.theme${th}` as MessageKey) }))} />
      </div>
      <ErrorAlert message={error} />
    </div>
  )
}

function BaseCurrencyForm() {
  const me = useMe().data
  const update = useUpdateSettings()
  const { t } = useI18n()
  const [currency, setCurrency] = useState(me?.baseCurrency ?? 'CHF')
  const [error, setError] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    try {
      await update.mutateAsync({ baseCurrency: currency })
    } catch (err) {
      setError(errorMessage(err))
    }
  }
  return (
    <form onSubmit={submit} className="flex flex-col gap-3">
      <p className="text-sm text-ink-2">
        {t('settings.baseCurrencyHelp')}
      </p>
      <div className="flex gap-2">
        <input className="input w-28 uppercase" list="base-currencies" maxLength={3} aria-label={t('settings.baseCurrency')} value={currency}
          onChange={(e) => setCurrency(e.target.value.toUpperCase())} />
        <datalist id="base-currencies">{COMMON_CURRENCIES.map((c) => <option key={c} value={c} />)}</datalist>
        <Button type="submit" loading={update.isPending} disabled={currency === me?.baseCurrency}>{t('common.save')}</Button>
      </div>
      <ErrorAlert message={error} />
    </form>
  )
}

const REASONS = [
  'SUCCESS', 'MFA_REQUIRED', 'BAD_CREDENTIALS', 'BAD_MFA_CODE', 'RECOVERY_CODE_USED',
  'LOCKED', 'DISABLED', 'RATE_LIMITED', 'UNKNOWN_USER',
]

function LoginHistory() {
  const history = useLoginHistory()
  const { t } = useI18n()
  if (history.isPending) return <Spinner />
  const events = history.data ?? []
  if (events.length === 0) return <p className="text-sm text-muted">{t('settings.noLogins')}</p>
  return (
    <ul className="divide-y divide-line text-sm">
      {events.map((e, i) => (
        <li key={i} className="flex items-start justify-between gap-3 py-2">
          <div className="min-w-0">
            <p className={e.success ? 'text-ink' : e.reason === 'MFA_REQUIRED' ? 'text-ink-2' : 'text-bad'}>{REASONS.includes(e.reason) ? t(`reason.${e.reason}` as MessageKey) : e.reason}</p>
            <p className="truncate text-xs text-muted" title={e.userAgent ?? ''}>{e.ipAddress} · {e.userAgent ?? t('settings.unknownBrowser')}</p>
          </div>
          <span className="shrink-0 text-xs text-muted">{dateTime(e.at)}</span>
        </li>
      ))}
    </ul>
  )
}

// ------------------------------------------------------------------ categories

function CategoriesTab() {
  const categories = useCategories()
  const remove = useDeleteCategory()
  const [editing, setEditing] = useState<Category | { kind: EntryKind } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const { t } = useI18n()

  async function onDelete(c: Category) {
    if (!confirm(t('categories.confirmDelete', { name: c.name }))) return
    setError(null)
    try {
      await remove.mutateAsync(c.id)
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  if (categories.isPending) return <Spinner />
  return (
    <>
      <ErrorAlert message={error} />
      <div className="mt-2 grid gap-4 lg:grid-cols-2">
        {(['EXPENSE', 'INCOME'] as EntryKind[]).map((kind) => (
          <Card key={kind} title={kind === 'EXPENSE' ? t('categories.expense') : t('categories.income')}
            actions={<Button onClick={() => setEditing({ kind })}>{t('common.add')}</Button>}>
            <ul className="divide-y divide-line">
              {(categories.data ?? []).filter((c) => c.kind === kind).map((c) => (
                <li key={c.id} className="flex items-center justify-between py-2 text-sm">
                  <span className="flex items-center gap-2">
                    <span className="size-3 rounded-full" style={{ background: c.color }} aria-hidden />{c.name}
                  </span>
                  <span className="flex gap-1">
                    <button type="button" className="rounded p-1.5 text-muted hover:text-ink" aria-label={t('categories.editName', { name: c.name })} onClick={() => setEditing(c)}>
                      <Pencil className="size-4" />
                    </button>
                    <button type="button" className="rounded p-1.5 text-muted hover:text-bad" aria-label={t('categories.deleteName', { name: c.name })} onClick={() => onDelete(c)}>
                      <Trash2 className="size-4" />
                    </button>
                  </span>
                </li>
              ))}
            </ul>
          </Card>
        ))}
      </div>
      <Modal title={editing && 'id' in editing ? t('categories.edit') : t('categories.new')} open={editing !== null} onClose={() => setEditing(null)}>
        {editing && <CategoryForm initial={editing} onDone={() => setEditing(null)} />}
      </Modal>
    </>
  )
}

function CategoryForm({ initial, onDone }: { initial: Category | { kind: EntryKind }; onDone: () => void }) {
  const save = useSaveCategory()
  const { t } = useI18n()
  const existing = 'id' in initial ? initial : null
  const [name, setName] = useState(existing?.name ?? '')
  const [color, setColor] = useState(existing?.color ?? '#2a78d6')
  const [error, setError] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    try {
      await save.mutateAsync({ id: existing?.id, name, kind: initial.kind, color })
      onDone()
    } catch (err) {
      setError(errorMessage(err))
    }
  }
  return (
    <form onSubmit={submit} className="flex flex-col gap-3">
      <Field label={t('common.name')}>
        {(id) => <input id={id} className="input" required maxLength={64} autoFocus value={name} onChange={(e) => setName(e.target.value)} />}
      </Field>
      <Field label={t('categories.color')}>
        {(id) => <input id={id} type="color" className="h-10 w-20 cursor-pointer rounded border border-line bg-surface" value={color} onChange={(e) => setColor(e.target.value)} />}
      </Field>
      <ErrorAlert message={error} />
      <div className="flex justify-end"><Button type="submit" variant="primary" loading={save.isPending}>{t('common.save')}</Button></div>
    </form>
  )
}

// ------------------------------------------------------------------ fx

function FxTab() {
  const me = useMe().data
  const rates = useFxRates()
  const save = useSaveFxRate()
  const remove = useDeleteFxRate()
  const { t } = useI18n()
  const base = me?.baseCurrency ?? 'CHF'
  const [currency, setCurrency] = useState(base === 'EUR' ? 'USD' : 'EUR')
  const [day, setDay] = useState(today())
  const [rate, setRate] = useState('')
  const [error, setError] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    const value = parseDecimal(rate)
    if (value === null || value <= 0) {
      setError(t('fx.ratePositive'))
      return
    }
    try {
      await save.mutateAsync({ currency, date: day, rate: value })
      setRate('')
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  const grouped = new Map<string, typeof rates.data>()
  ;(rates.data ?? []).forEach((r) => grouped.set(r.currency, [...(grouped.get(r.currency) ?? []), r]))

  return (
    <div className="grid gap-4 lg:grid-cols-[1fr_2fr]">
      <Card title={t('fx.addRate')}>
        <form onSubmit={submit} className="flex flex-col gap-3">
          <p className="text-sm text-ink-2">
            {t('fx.helpBefore')} <strong>{t('fx.helpUnit')}</strong> {t('fx.helpAfter', { base })}
          </p>
          <div className="grid grid-cols-2 gap-3">
            <Field label={t('common.currency')}>
              {(id) => (
                <>
                  <input id={id} className="input uppercase" list="fx-currencies" maxLength={3} required value={currency}
                    onChange={(e) => setCurrency(e.target.value.toUpperCase())} />
                  <datalist id="fx-currencies">{COMMON_CURRENCIES.filter((c) => c !== base).map((c) => <option key={c} value={c} />)}</datalist>
                </>
              )}
            </Field>
            <Field label={t('common.date')}>
              {(id) => <input id={id} type="date" className="input" required value={day} onChange={(e) => setDay(e.target.value)} />}
            </Field>
          </div>
          <Field label={`1 ${currency || '…'} = ? ${base}`}>
            {(id) => <input id={id} className="input tabular" inputMode="decimal" required placeholder="0.95" value={rate} onChange={(e) => setRate(e.target.value)} />}
          </Field>
          <ErrorAlert message={error} />
          <div><Button type="submit" variant="primary" loading={save.isPending}>{t('fx.saveRate')}</Button></div>
        </form>
      </Card>
      <Card title={t('fx.ratesTo', { base })}>
        {rates.isPending ? <Spinner /> : grouped.size === 0 ? (
          <EmptyState title={t('fx.emptyTitle')}>{t('fx.emptyHelp', { base })}</EmptyState>
        ) : (
          <div className="flex flex-col gap-4">
            {[...grouped.entries()].map(([ccy, list]) => (
              <div key={ccy}>
                <h3 className="mb-1 flex items-center gap-2 text-sm font-semibold">{ccy} <Badge>{list!.length}</Badge></h3>
                <ul className="divide-y divide-line text-sm">
                  {list!.slice(0, 12).map((r) => (
                    <li key={r.id} className="flex items-center justify-between py-1.5">
                      <span className="tabular text-ink-2">{date(r.date)}</span>
                      <span className="flex items-center gap-2">
                        <span className="tabular">1 {ccy} = {number(r.rate, 6)} {base}</span>
                        <button type="button" className="rounded p-1 text-muted hover:text-bad" aria-label={t('fx.deleteRate')}
                          onClick={() => remove.mutate(r.id)}>
                          <Trash2 className="size-4" />
                        </button>
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        )}
      </Card>
      {/* min-w-0: the wide table scrolls inside the card instead of widening the grid on phones */}
      <div className="min-w-0 lg:col-span-2">
        <EcbRatesCard base={base} isAdmin={me?.role === 'ADMIN'} />
      </div>
    </div>
  )
}

function EcbRatesCard({ base, isAdmin }: { base: string; isAdmin: boolean }) {
  const { t } = useI18n()
  const central = useCentralRates()
  const status = useEcbStatus(isAdmin)
  const refresh = useRefreshEcb()
  const [error, setError] = useState<string | null>(null)
  const data = central.data

  async function refreshNow() {
    setError(null)
    try {
      await refresh.mutateAsync()
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  return (
    <Card title={t('fx.ecbTitle', { base })}>
      <p className="mb-3 text-sm text-ink-2">
        {t('fx.ecbHelp')}{' '}
        {data?.latestDate && <span className="text-muted">{t('fx.ecbLatest', { date: date(data.latestDate) })}</span>}
      </p>
      {isAdmin && (
        <div className="mb-3 flex flex-wrap items-center gap-3 rounded-lg bg-surface-2 px-3 py-2 text-xs text-ink-2">
          <span>
            {status.data?.autoUpdate ? t('fx.ecbAuto') : t('fx.ecbAutoOff')}
            {status.data?.lastSuccess && <> · {t('fx.ecbLastSuccess', { date: dateTime(status.data.lastSuccess) })}</>}
          </span>
          {status.data?.lastError && <span className="text-bad">{t('fx.ecbLastError', { error: status.data.lastError })}</span>}
          <Button variant="secondary" onClick={refreshNow} loading={refresh.isPending}>
            <RefreshCw className="size-4" /> {t('fx.ecbRefresh')}
          </Button>
        </div>
      )}
      <ErrorAlert message={error} />
      {central.isPending ? <Spinner /> : !data || data.rates.length === 0 ? (
        <EmptyState title={t('fx.ecbEmptyTitle')}>{t('fx.ecbEmptyHelp')}</EmptyState>
      ) : (
        <div className="-mx-4 overflow-x-auto sm:mx-0">
          <table className="w-full min-w-[32rem] text-sm">
            <thead>
              <tr className="border-b border-line text-left text-xs text-muted">
                <th className="px-4 py-2 font-medium sm:px-2">{t('common.currency')}</th>
                <th className="px-2 py-2 text-right font-medium">{t('fx.ecbRate', { base })}</th>
                <th className="px-2 py-2 font-medium">{t('common.date')}</th>
                <th className="px-2 py-2 font-medium">{t('fx.ecbUsed')}</th>
              </tr>
            </thead>
            <tbody>
              {data.rates.map((r) => (
                <tr key={r.currency} className="border-b border-line last:border-0">
                  <td className="px-4 py-1.5 font-medium sm:px-2">{r.currency}</td>
                  <td className={`tabular px-2 py-1.5 text-right ${r.manualRate !== null ? 'text-muted line-through' : ''}`}>
                    {number(r.rate, 6)}
                  </td>
                  <td className="tabular px-2 py-1.5 text-ink-2">{date(r.date)}</td>
                  <td className="px-2 py-1.5">
                    {r.manualRate !== null
                      ? <Badge tone="accent">{t('fx.ecbManual', { rate: number(r.manualRate, 6) })}</Badge>
                      : <Badge>{t('fx.ecbSource')}</Badge>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  )
}
