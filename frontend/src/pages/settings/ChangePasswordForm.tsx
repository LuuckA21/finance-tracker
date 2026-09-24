import { useState, type FormEvent } from 'react'
import { refreshCsrf, errorMessage } from '../../api/client'
import { useChangePassword } from '../../api/hooks'
import { Button, ErrorAlert, Field } from '../../components/ui'
import { useI18n } from '../../i18n'

export function ChangePasswordForm({ onDone }: { onDone?: () => void }) {
  const change = useChangePassword()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [ok, setOk] = useState(false)
  const { t } = useI18n()

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setOk(false)
    if (next !== confirm) {
      setError(t('password.mismatch'))
      return
    }
    if (next.length < 12) {
      setError(t('password.tooShort'))
      return
    }
    try {
      await change.mutateAsync({ currentPassword: current, newPassword: next })
      await refreshCsrf()
      setCurrent('')
      setNext('')
      setConfirm('')
      setOk(true)
      onDone?.()
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-3">
      <Field label={t('password.current')}>
        {(id) => <input id={id} type="password" className="input" autoComplete="current-password" required value={current} onChange={(e) => setCurrent(e.target.value)} />}
      </Field>
      <Field label={t('password.new')} hint={t('password.newHint')}>
        {(id) => <input id={id} type="password" className="input" autoComplete="new-password" required minLength={12} maxLength={128} value={next} onChange={(e) => setNext(e.target.value)} />}
      </Field>
      <Field label={t('password.confirm')}>
        {(id) => <input id={id} type="password" className="input" autoComplete="new-password" required value={confirm} onChange={(e) => setConfirm(e.target.value)} />}
      </Field>
      <ErrorAlert message={error} />
      {ok && <p className="text-sm text-good">{t('password.changed')}</p>}
      <div>
        <Button type="submit" variant="primary" loading={change.isPending}>{t('password.submit')}</Button>
      </div>
    </form>
  )
}
