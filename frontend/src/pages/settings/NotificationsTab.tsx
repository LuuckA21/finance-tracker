import { useState, type FormEvent } from 'react'
import { MailCheck } from 'lucide-react'
import { errorMessage } from '../../api/client'
import {
  useConfirmEmail,
  useNotificationSettings,
  useRemoveEmail,
  useRequestEmailCode,
  useSendTestEmail,
  useUpdateNotifications,
} from '../../api/hooks'
import type { NotificationSettings } from '../../api/types'
import { Button, Card, ErrorAlert, Field, Spinner } from '../../components/ui'
import { useI18n, type MessageKey } from '../../i18n'

const ALERTS: { key: 'budgetAlerts' | 'goalAlerts' | 'monthlySummary'; label: MessageKey; help: MessageKey }[] = [
  { key: 'budgetAlerts', label: 'notify.budget', help: 'notify.budgetHelp' },
  { key: 'goalAlerts', label: 'notify.goals', help: 'notify.goalsHelp' },
  { key: 'monthlySummary', label: 'notify.monthly', help: 'notify.monthlyHelp' },
]

/** Email notifications: the address (confirmed with a code) and which alerts to get. */
export function NotificationsTab() {
  const settings = useNotificationSettings()
  const { t } = useI18n()
  if (settings.isPending) return <Spinner />
  if (!settings.data) return <ErrorAlert message={errorMessage(settings.error)} />
  const data = settings.data
  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
      <Card title={t('notify.emailTitle')}>
        {data.mailEnabled ? <EmailSection data={data} /> : (
          <p className="rounded-lg bg-warn-soft px-3 py-2 text-sm text-warn-ink" role="status">{t('notify.disabled')}</p>
        )}
      </Card>
      <Card title={t('notify.alertsTitle')}><AlertsForm data={data} /></Card>
    </div>
  )
}

function EmailSection({ data }: { data: NotificationSettings }) {
  const remove = useRemoveEmail()
  const test = useSendTestEmail()
  const [changing, setChanging] = useState(false)
  const [otherAddress, setOtherAddress] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [sent, setSent] = useState(false)
  const { t } = useI18n()

  async function run(action: () => Promise<unknown>) {
    setError(null)
    setSent(false)
    try {
      await action()
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm text-ink-2">{t('notify.emailHelp')}</p>
      {data.email && (
        <div className="flex flex-col gap-3">
          <p className="flex items-center gap-2 text-sm">
            <MailCheck className="size-4 shrink-0 text-good" aria-hidden />
            <span>{t('notify.sendingTo')} <strong className="break-all">{data.email}</strong></span>
          </p>
          <div className="flex flex-wrap gap-2">
            <Button loading={test.isPending} onClick={() => run(async () => { await test.mutateAsync(); setSent(true) })}>
              {t('notify.sendTest')}
            </Button>
            {!changing && !data.pendingEmail && <Button onClick={() => setChanging(true)}>{t('notify.change')}</Button>}
            <Button variant="danger" loading={remove.isPending}
              onClick={() => confirm(t('notify.confirmRemove')) && run(() => remove.mutateAsync())}>
              {t('notify.remove')}
            </Button>
          </div>
          {sent && <p className="text-sm text-good" role="status">{t('notify.testSent', { email: data.email })}</p>}
        </div>
      )}
      {data.pendingEmail && !otherAddress
        ? <CodeForm pending={data.pendingEmail} onDone={() => setChanging(false)} onOtherAddress={() => setOtherAddress(true)} />
        : (!data.email || changing || otherAddress) && (
          <AddressForm current={data.email} onSent={() => setOtherAddress(false)}
            onCancel={data.email || otherAddress ? () => { setChanging(false); setOtherAddress(false) } : undefined} />
        )}
      <ErrorAlert message={error} />
    </div>
  )
}

function AddressForm({ current, onSent, onCancel }: { current: string | null; onSent: () => void; onCancel?: () => void }) {
  const request = useRequestEmailCode()
  const [email, setEmail] = useState('')
  const [error, setError] = useState<string | null>(null)
  const { t } = useI18n()

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    try {
      await request.mutateAsync(email.trim())
      onSent()
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-3">
      <Field label={current ? t('notify.newAddress') : t('notify.address')} hint={t('notify.addressHint')}>
        {(id) => <input id={id} type="email" className="input" required maxLength={254} autoComplete="email"
          value={email} onChange={(e) => setEmail(e.target.value)} />}
      </Field>
      <ErrorAlert message={error} />
      <div className="flex flex-wrap gap-2">
        <Button type="submit" variant="primary" loading={request.isPending}>{t('notify.sendCode')}</Button>
        {onCancel && <Button onClick={onCancel}>{t('common.cancel')}</Button>}
      </div>
    </form>
  )
}

function CodeForm({ pending, onDone, onOtherAddress }: { pending: string; onDone: () => void; onOtherAddress: () => void }) {
  const confirmEmail = useConfirmEmail()
  const request = useRequestEmailCode()
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [resent, setResent] = useState(false)
  const { t } = useI18n()

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    try {
      await confirmEmail.mutateAsync(code.trim())
      onDone()
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  async function resend() {
    setError(null)
    setResent(false)
    try {
      await request.mutateAsync(pending)
      setResent(true)
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-3">
      <p className="text-sm">{t('notify.codeSent', { email: pending })}</p>
      <Field label={t('notify.code')} hint={t('notify.codeHint')}>
        {(id) => <input id={id} className="input tabular max-w-40 tracking-widest" required inputMode="numeric"
          autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} />}
      </Field>
      <ErrorAlert message={error} />
      {resent && <p className="text-sm text-good" role="status">{t('notify.codeResent')}</p>}
      <div className="flex flex-wrap gap-2">
        <Button type="submit" variant="primary" loading={confirmEmail.isPending}>{t('notify.confirm')}</Button>
        <Button variant="ghost" loading={request.isPending} onClick={resend}>{t('notify.resend')}</Button>
        <Button variant="ghost" onClick={onOtherAddress}>{t('notify.otherAddress')}</Button>
      </div>
    </form>
  )
}

type AlertKey = (typeof ALERTS)[number]['key']

function AlertsForm({ data }: { data: NotificationSettings }) {
  const update = useUpdateNotifications()
  // Local copy: a switch must follow the click at once; it goes back if the server refuses
  const [values, setValues] = useState<Record<AlertKey, boolean>>(() => ({
    budgetAlerts: data.budgetAlerts, goalAlerts: data.goalAlerts, monthlySummary: data.monthlySummary,
  }))
  const [error, setError] = useState<string | null>(null)
  const { t } = useI18n()

  async function toggle(key: AlertKey, value: boolean) {
    setError(null)
    setValues((v) => ({ ...v, [key]: value }))
    try {
      await update.mutateAsync({ [key]: value })
    } catch (err) {
      setValues((v) => ({ ...v, [key]: !value }))
      setError(errorMessage(err))
    }
  }

  return (
    <div className="flex flex-col gap-3">
      <ul className="flex flex-col gap-3">
        {ALERTS.map((alert) => (
          <li key={alert.key} className="flex items-start gap-3 text-sm">
            <input id={`notify-${alert.key}`} type="checkbox" className="mt-0.5" checked={values[alert.key]}
              onChange={(e) => toggle(alert.key, e.target.checked)}
              aria-describedby={`notify-${alert.key}-help`} />
            <div>
              <label htmlFor={`notify-${alert.key}`} className="font-medium">{t(alert.label)}</label>
              <p id={`notify-${alert.key}-help`} className="text-xs text-ink-2">{t(alert.help)}</p>
            </div>
          </li>
        ))}
      </ul>
      <p className="text-xs text-muted">{data.email ? t('notify.fromNow') : t('notify.needsAddress')}</p>
      <ErrorAlert message={error} />
    </div>
  )
}
