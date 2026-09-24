import { useState, type FormEvent } from 'react'
import { NavLink, useParams } from 'react-router'
import { Pencil, Trash2 } from 'lucide-react'
import { errorMessage } from '../../api/client'
import {
  useCategories,
  useDeleteCategory,
  useDeleteFxRate,
  useFxRates,
  useLoginHistory,
  useMe,
  useSaveCategory,
  useSaveFxRate,
  useUpdateSettings,
} from '../../api/hooks'
import type { Category, EntryKind } from '../../api/types'
import { Badge, Button, Card, EmptyState, ErrorAlert, Field, Modal, PageHeader, Spinner } from '../../components/ui'
import { COMMON_CURRENCIES, date, dateTime, number, parseDecimal, today } from '../../lib/format'
import { ChangePasswordForm } from './ChangePasswordForm'
import { MfaSection } from './MfaSection'

const TABS = [
  { id: 'account', label: 'Account e sicurezza' },
  { id: 'categorie', label: 'Categorie' },
  { id: 'cambi', label: 'Tassi di cambio' },
]

export function SettingsPage() {
  const tab = useParams().tab ?? 'account'
  return (
    <>
      <PageHeader title="Impostazioni" />
      <nav className="mb-5 flex gap-1 overflow-x-auto border-b border-line" aria-label="Sezioni impostazioni">
        {TABS.map((t) => (
          <NavLink key={t.id} to={`/impostazioni/${t.id}`}
            className={() => `whitespace-nowrap border-b-2 px-3 py-2 text-sm ${tab === t.id ? 'border-accent font-medium text-ink' : 'border-transparent text-ink-2 hover:text-ink'}`}>
            {t.label}
          </NavLink>
        ))}
      </nav>
      {tab === 'categorie' ? <CategoriesTab /> : tab === 'cambi' ? <FxTab /> : <AccountTab />}
    </>
  )
}

// ------------------------------------------------------------------ account

function AccountTab() {
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Card title="Valuta di base"><BaseCurrencyForm /></Card>
      <Card title="Verifica in due passaggi"><MfaSection /></Card>
      <Card title="Cambia password"><ChangePasswordForm /></Card>
      <Card title="Accessi recenti"><LoginHistory /></Card>
    </div>
  )
}

function BaseCurrencyForm() {
  const me = useMe().data
  const update = useUpdateSettings()
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
        Tutte le dashboard sono convertite in questa valuta. I tassi di cambio sono legati alla valuta di base: se la cambi, dovrai inserire i tassi verso la nuova.
      </p>
      <div className="flex gap-2">
        <input className="input w-28 uppercase" list="base-currencies" maxLength={3} aria-label="Valuta di base" value={currency}
          onChange={(e) => setCurrency(e.target.value.toUpperCase())} />
        <datalist id="base-currencies">{COMMON_CURRENCIES.map((c) => <option key={c} value={c} />)}</datalist>
        <Button type="submit" loading={update.isPending} disabled={currency === me?.baseCurrency}>Salva</Button>
      </div>
      <ErrorAlert message={error} />
    </form>
  )
}

const REASONS: Record<string, string> = {
  SUCCESS: 'Accesso riuscito',
  MFA_REQUIRED: 'Password corretta, in attesa del codice',
  BAD_CREDENTIALS: 'Password errata',
  BAD_MFA_CODE: 'Codice 2FA errato',
  RECOVERY_CODE_USED: 'Accesso con codice di recupero',
  LOCKED: 'Account bloccato temporaneamente',
  DISABLED: 'Account disattivato',
  RATE_LIMITED: 'Troppi tentativi',
  UNKNOWN_USER: 'Utente sconosciuto',
}

function LoginHistory() {
  const history = useLoginHistory()
  if (history.isPending) return <Spinner />
  const events = history.data ?? []
  if (events.length === 0) return <p className="text-sm text-muted">Nessun accesso registrato.</p>
  return (
    <ul className="divide-y divide-line text-sm">
      {events.map((e, i) => (
        <li key={i} className="flex items-start justify-between gap-3 py-2">
          <div className="min-w-0">
            <p className={e.success ? 'text-ink' : e.reason === 'MFA_REQUIRED' ? 'text-ink-2' : 'text-bad'}>{REASONS[e.reason] ?? e.reason}</p>
            <p className="truncate text-xs text-muted" title={e.userAgent ?? ''}>{e.ipAddress} · {e.userAgent ?? 'browser sconosciuto'}</p>
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

  async function onDelete(c: Category) {
    if (!confirm(`Eliminare la categoria “${c.name}”?`)) return
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
          <Card key={kind} title={kind === 'EXPENSE' ? 'Categorie di uscita' : 'Categorie di entrata'}
            actions={<Button onClick={() => setEditing({ kind })}>Aggiungi</Button>}>
            <ul className="divide-y divide-line">
              {(categories.data ?? []).filter((c) => c.kind === kind).map((c) => (
                <li key={c.id} className="flex items-center justify-between py-2 text-sm">
                  <span className="flex items-center gap-2">
                    <span className="size-3 rounded-full" style={{ background: c.color }} aria-hidden />{c.name}
                  </span>
                  <span className="flex gap-1">
                    <button type="button" className="rounded p-1.5 text-muted hover:text-ink" aria-label={`Modifica ${c.name}`} onClick={() => setEditing(c)}>
                      <Pencil className="size-4" />
                    </button>
                    <button type="button" className="rounded p-1.5 text-muted hover:text-bad" aria-label={`Elimina ${c.name}`} onClick={() => onDelete(c)}>
                      <Trash2 className="size-4" />
                    </button>
                  </span>
                </li>
              ))}
            </ul>
          </Card>
        ))}
      </div>
      <Modal title={editing && 'id' in editing ? 'Modifica categoria' : 'Nuova categoria'} open={editing !== null} onClose={() => setEditing(null)}>
        {editing && <CategoryForm initial={editing} onDone={() => setEditing(null)} />}
      </Modal>
    </>
  )
}

function CategoryForm({ initial, onDone }: { initial: Category | { kind: EntryKind }; onDone: () => void }) {
  const save = useSaveCategory()
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
      <Field label="Nome">
        {(id) => <input id={id} className="input" required maxLength={64} autoFocus value={name} onChange={(e) => setName(e.target.value)} />}
      </Field>
      <Field label="Colore">
        {(id) => <input id={id} type="color" className="h-10 w-20 cursor-pointer rounded border border-line bg-surface" value={color} onChange={(e) => setColor(e.target.value)} />}
      </Field>
      <ErrorAlert message={error} />
      <div className="flex justify-end"><Button type="submit" variant="primary" loading={save.isPending}>Salva</Button></div>
    </form>
  )
}

// ------------------------------------------------------------------ fx

function FxTab() {
  const me = useMe().data
  const rates = useFxRates()
  const save = useSaveFxRate()
  const remove = useDeleteFxRate()
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
      setError('Inserisci un tasso positivo.')
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
      <Card title="Aggiungi tasso">
        <form onSubmit={submit} className="flex flex-col gap-3">
          <p className="text-sm text-ink-2">
            Indica quanto vale <strong>1 unità</strong> della valuta in {base}. Per ogni data viene usato l'ultimo tasso disponibile
            (o il primo, per date precedenti).
          </p>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Valuta">
              {(id) => (
                <>
                  <input id={id} className="input uppercase" list="fx-currencies" maxLength={3} required value={currency}
                    onChange={(e) => setCurrency(e.target.value.toUpperCase())} />
                  <datalist id="fx-currencies">{COMMON_CURRENCIES.filter((c) => c !== base).map((c) => <option key={c} value={c} />)}</datalist>
                </>
              )}
            </Field>
            <Field label="Data">
              {(id) => <input id={id} type="date" className="input" required value={day} onChange={(e) => setDay(e.target.value)} />}
            </Field>
          </div>
          <Field label={`1 ${currency || '…'} = ? ${base}`}>
            {(id) => <input id={id} className="input tabular" inputMode="decimal" required placeholder="0.95" value={rate} onChange={(e) => setRate(e.target.value)} />}
          </Field>
          <ErrorAlert message={error} />
          <div><Button type="submit" variant="primary" loading={save.isPending}>Salva tasso</Button></div>
        </form>
      </Card>
      <Card title={`Tassi verso ${base}`}>
        {rates.isPending ? <Spinner /> : grouped.size === 0 ? (
          <EmptyState title="Nessun tasso">Servono solo se registri movimenti o posizioni in valute diverse da {base}.</EmptyState>
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
                        <button type="button" className="rounded p-1 text-muted hover:text-bad" aria-label="Elimina tasso"
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
    </div>
  )
}
