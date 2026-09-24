import { useState, type FormEvent } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { KeyRound, LockKeyhole } from 'lucide-react'
import { ApiError, errorMessage, post, refreshCsrf } from '../api/client'
import { Button, ErrorAlert, Field } from '../components/ui'
import { useI18n } from '../i18n'

export function LoginPage() {
  const [step, setStep] = useState<'password' | 'mfa'>('password')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const navigate = useNavigate()
  const location = useLocation()
  const qc = useQueryClient()
  const { t } = useI18n()

  const from = (location.state as { from?: string } | null)?.from ?? '/'

  async function finish() {
    await refreshCsrf()
    await qc.resetQueries({ queryKey: ['me'] })
    navigate(from === '/login' ? '/' : from, { replace: true })
  }

  async function submitPassword(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await refreshCsrf()
      const res = await post<{ mfaRequired: boolean }>('/api/auth/login', { username, password })
      setPassword('')
      if (res.mfaRequired) {
        await refreshCsrf()
        setStep('mfa')
      } else {
        await finish()
      }
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  async function submitCode(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await post('/api/auth/login/mfa', { code })
      await finish()
    } catch (err) {
      setError(errorMessage(err))
      if (err instanceof ApiError && err.code === 'mfa_expired') {
        setStep('password')
      }
    } finally {
      setBusy(false)
      setCode('')
    }
  }

  return (
    <main className="flex min-h-dvh items-center justify-center px-4">
      <div className="card w-full max-w-sm p-6">
        <div className="mb-6 flex items-center gap-3">
          <div className="flex size-10 items-center justify-center rounded-xl bg-accent-soft text-accent">
            {step === 'password' ? <LockKeyhole className="size-5" /> : <KeyRound className="size-5" />}
          </div>
          <div>
            <h1 className="text-lg font-semibold">{t('app.name')}</h1>
            <p className="text-sm text-ink-2">{step === 'password' ? t('login.subtitle') : t('login.mfaSubtitle')}</p>
          </div>
        </div>

        {step === 'password' ? (
          <form onSubmit={submitPassword} className="flex flex-col gap-4">
            <Field label={t('login.username')}>
              {(id) => (
                <input id={id} className="input" autoComplete="username" autoCapitalize="none" required
                  value={username} onChange={(e) => setUsername(e.target.value)} />
              )}
            </Field>
            <Field label={t('login.password')}>
              {(id) => (
                <input id={id} className="input" type="password" autoComplete="current-password" required
                  value={password} onChange={(e) => setPassword(e.target.value)} />
              )}
            </Field>
            <ErrorAlert message={error} />
            <Button type="submit" variant="primary" loading={busy}>{t('login.submit')}</Button>
          </form>
        ) : (
          <form onSubmit={submitCode} className="flex flex-col gap-4">
            <Field label={t('login.code')} hint={t('login.codeHint')}>
              {(id) => (
                <input id={id} className="input tabular text-center text-lg tracking-widest" autoComplete="one-time-code"
                  inputMode="text" required autoFocus maxLength={16} value={code} onChange={(e) => setCode(e.target.value)} />
              )}
            </Field>
            <ErrorAlert message={error} />
            <Button type="submit" variant="primary" loading={busy}>{t('login.verify')}</Button>
            <Button variant="ghost" onClick={() => { setStep('password'); setError(null) }}>{t('login.back')}</Button>
          </form>
        )}
      </div>
    </main>
  )
}
