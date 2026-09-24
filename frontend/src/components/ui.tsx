import { useEffect, useId, useRef, type ButtonHTMLAttributes, type ReactNode } from 'react'
import { Link } from 'react-router'
import { AlertTriangle, Loader2, X } from 'lucide-react'

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger'

const VARIANTS: Record<Variant, string> = {
  primary: 'bg-accent text-white hover:bg-accent-hover',
  secondary: 'bg-surface text-ink border border-line hover:bg-surface-2',
  ghost: 'text-ink-2 hover:bg-surface-2 hover:text-ink',
  danger: 'bg-surface text-bad border border-line hover:bg-bad-soft',
}

export function Button({
  variant = 'secondary',
  loading = false,
  className = '',
  children,
  disabled,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; loading?: boolean }) {
  return (
    <button
      type="button"
      {...props}
      disabled={disabled || loading}
      className={`inline-flex items-center justify-center gap-1.5 rounded-lg px-3 py-2 text-sm font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${VARIANTS[variant]} ${className}`}
    >
      {loading && <Loader2 className="size-4 animate-spin" aria-hidden />}
      {children}
    </button>
  )
}

export function Field({
  label,
  error,
  hint,
  children,
}: {
  label: string
  error?: string
  hint?: string
  children: (id: string) => ReactNode
}) {
  const id = useId()
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-xs font-medium text-ink-2">
        {label}
      </label>
      {children(id)}
      {hint && !error && <p className="text-xs text-muted">{hint}</p>}
      {error && <p className="text-xs text-bad">{error}</p>}
    </div>
  )
}

export function Card({ title, actions, children, className = '' }: {
  title?: ReactNode
  actions?: ReactNode
  children: ReactNode
  className?: string
}) {
  return (
    <section className={`card p-4 sm:p-5 ${className}`}>
      {(title || actions) && (
        <header className="mb-4 flex flex-wrap items-center justify-between gap-2">
          {title && <h2 className="text-sm font-semibold text-ink">{title}</h2>}
          {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
        </header>
      )}
      {children}
    </section>
  )
}

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 className="text-xl font-semibold tracking-tight text-ink">{title}</h1>
        {subtitle && <p className="mt-1 text-sm text-ink-2">{subtitle}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  )
}

export function ErrorAlert({ message }: { message: string | null | undefined }) {
  if (!message) return null
  return (
    <div role="alert" className="rounded-lg bg-bad-soft px-3 py-2 text-sm text-bad">
      {message}
    </div>
  )
}

/** Warns that some amounts are excluded from totals because an exchange rate is missing. */
export function MissingRatesNotice({ currencies, baseCurrency }: { currencies: string[] | undefined; baseCurrency?: string }) {
  if (!currencies || currencies.length === 0) return null
  return (
    <div role="status" className="mb-4 flex items-start gap-2 rounded-lg bg-warn-soft px-3 py-2 text-sm text-warn-ink">
      <AlertTriangle className="mt-0.5 size-4 shrink-0" aria-hidden />
      <span>
        Mancano i tassi di cambio per <strong>{currencies.join(', ')}</strong>
        {baseCurrency ? ` → ${baseCurrency}` : ''}: questi importi sono esclusi dai totali. Aggiungili in{' '}
        <Link className="underline" to="/impostazioni/cambi">Impostazioni › Tassi di cambio</Link>.
      </span>
    </div>
  )
}

export function Spinner({ label = 'Caricamento…' }: { label?: string }) {
  return (
    <div className="flex items-center gap-2 py-8 text-sm text-muted" role="status">
      <Loader2 className="size-4 animate-spin" aria-hidden />
      {label}
    </div>
  )
}

export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="rounded-xl border border-dashed border-line px-6 py-10 text-center">
      <p className="text-sm font-medium text-ink">{title}</p>
      {children && <div className="mt-2 text-sm text-ink-2">{children}</div>}
    </div>
  )
}

/** Stat tile: label, value, optional delta line. */
export function StatTile({ label, value, sub, tone }: {
  label: string
  value: ReactNode
  sub?: ReactNode
  tone?: 'good' | 'bad'
}) {
  return (
    <div className="card p-4">
      <p className="text-xs font-medium text-ink-2">{label}</p>
      <p className="mt-1 text-xl font-semibold tracking-tight text-ink sm:text-2xl">{value}</p>
      {sub && <p className={`mt-1 text-xs ${tone === 'good' ? 'text-good' : tone === 'bad' ? 'text-bad' : 'text-muted'}`}>{sub}</p>}
    </div>
  )
}

export function Modal({ title, open, onClose, children, footer, wide = false }: {
  title: string
  open: boolean
  onClose: () => void
  children: ReactNode
  footer?: ReactNode
  wide?: boolean
}) {
  const ref = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    if (open && !dialog.open) dialog.showModal()
    if (!open && dialog.open) dialog.close()
  }, [open])

  return (
    <dialog
      ref={ref}
      onClose={onClose}
      onCancel={(e) => {
        e.preventDefault()
        onClose()
      }}
      className={`m-auto w-[calc(100%-2rem)] ${wide ? 'max-w-2xl' : 'max-w-md'} rounded-2xl border border-line bg-surface p-0 text-ink shadow-xl backdrop:bg-black/40`}
    >
      {open && (
        <div className="flex max-h-[85vh] flex-col">
          <header className="flex items-center justify-between border-b border-line px-5 py-3">
            <h2 className="text-base font-semibold">{title}</h2>
            <button type="button" onClick={onClose} className="rounded-md p-1 text-muted hover:bg-surface-2" aria-label="Chiudi">
              <X className="size-4" />
            </button>
          </header>
          <div className="overflow-y-auto px-5 py-4">{children}</div>
          {footer && <footer className="flex justify-end gap-2 border-t border-line px-5 py-3">{footer}</footer>}
        </div>
      )}
    </dialog>
  )
}

/** Segmented control for small option sets (tipo, granularità, ...). */
export function Segmented<T extends string>({ value, options, onChange, label }: {
  value: T
  options: { value: T; label: string }[]
  onChange: (value: T) => void
  label: string
}) {
  return (
    <div role="radiogroup" aria-label={label} className="inline-flex w-fit rounded-lg border border-line bg-surface-2 p-0.5">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          role="radio"
          aria-checked={value === o.value}
          onClick={() => onChange(o.value)}
          className={`rounded-md px-3 py-1 text-sm transition-colors ${value === o.value ? 'bg-surface font-medium text-ink shadow-sm' : 'text-ink-2 hover:text-ink'}`}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: 'neutral' | 'good' | 'bad' | 'accent' }) {
  const tones = {
    neutral: 'bg-surface-2 text-ink-2',
    good: 'bg-surface-2 text-good',
    bad: 'bg-bad-soft text-bad',
    accent: 'bg-accent-soft text-accent',
  }
  return <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${tones[tone]}`}>{children}</span>
}
