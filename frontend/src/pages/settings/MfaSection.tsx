import { useEffect, useState, type FormEvent } from 'react'
import QRCode from 'qrcode'
import { ShieldCheck, ShieldOff } from 'lucide-react'
import { errorMessage } from '../../api/client'
import { useMe, useMfaDisable, useMfaEnable, useMfaSetup, useRegenerateRecoveryCodes } from '../../api/hooks'
import { Badge, Button, ErrorAlert, Field, Modal } from '../../components/ui'
import { useI18n } from '../../i18n'

/** Two-factor authentication: enrolment with QR code, recovery codes, disabling. */
export function MfaSection() {
  const me = useMe().data
  const [mode, setMode] = useState<'setup' | 'disable' | 'regenerate' | null>(null)
  const [codes, setCodes] = useState<string[] | null>(null)
  const { t } = useI18n()

  if (!me) return null
  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-2">
        {me.mfaEnabled ? <ShieldCheck className="size-5 text-good" /> : <ShieldOff className="size-5 text-muted" />}
        <span className="text-sm">
          {me.mfaEnabled ? t('mfa.enabled') : t('mfa.disabled')}
        </span>
        {me.mfaEnabled && (
          <Badge tone={me.recoveryCodesRemaining < 3 ? 'bad' : 'neutral'}>{t('mfa.recoveryRemaining', { count: me.recoveryCodesRemaining })}</Badge>
        )}
      </div>
      <p className="text-sm text-ink-2">
        {t('mfa.help')}
      </p>
      <div className="flex flex-wrap gap-2">
        {me.mfaEnabled ? (
          <>
            <Button onClick={() => setMode('regenerate')}>{t('mfa.newCodes')}</Button>
            <Button variant="danger" onClick={() => setMode('disable')}>{t('mfa.disable')}</Button>
          </>
        ) : (
          <Button variant="primary" onClick={() => setMode('setup')}>{t('mfa.enableTitle')}</Button>
        )}
      </div>

      <Modal title={t('mfa.enableTitle')} open={mode === 'setup'} onClose={() => setMode(null)}>
        {mode === 'setup' && <SetupFlow onEnabled={(c) => { setMode(null); setCodes(c) }} />}
      </Modal>
      <Modal title={t('mfa.disableTitle')} open={mode === 'disable'} onClose={() => setMode(null)}>
        {mode === 'disable' && <DisableForm onDone={() => setMode(null)} />}
      </Modal>
      <Modal title={t('mfa.newCodes')} open={mode === 'regenerate'} onClose={() => setMode(null)}>
        {mode === 'regenerate' && <RegenerateForm onDone={(c) => { setMode(null); setCodes(c) }} />}
      </Modal>
      <Modal title={t('mfa.codesTitle')} open={codes !== null} onClose={() => setCodes(null)}
        footer={<Button variant="primary" onClick={() => setCodes(null)}>{t('mfa.codesSaved')}</Button>}>
        {codes && <RecoveryCodes codes={codes} />}
      </Modal>
    </div>
  )
}

function SetupFlow({ onEnabled }: { onEnabled: (codes: string[]) => void }) {
  const setup = useMfaSetup()
  const enable = useMfaEnable()
  const [qr, setQr] = useState<string | null>(null)
  const [code, setCode] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const { t } = useI18n()
  const { mutate } = setup

  useEffect(() => {
    mutate(undefined, {
      onSuccess: async (data) => setQr(await QRCode.toDataURL(data.otpauthUri, { margin: 1, width: 200 })),
      onError: (err) => setError(errorMessage(err)),
    })
  }, [mutate])

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    try {
      const res = await enable.mutateAsync({ password, code: code.replace(/\s/g, '') })
      onEnabled(res.recoveryCodes)
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4">
      <ol className="list-decimal space-y-1 pl-5 text-sm text-ink-2">
        <li>{t('mfa.stepScan')}</li>
        <li>{t('mfa.stepCode')}</li>
      </ol>
      <div className="flex justify-center">
        {qr ? <img src={qr} alt={t('mfa.qrAlt')} className="rounded-lg bg-white p-2" width={200} height={200} /> :
          <div className="size-[200px] animate-pulse rounded-lg bg-surface-2" />}
      </div>
      {setup.data && (
        <details className="text-xs text-ink-2">
          <summary className="cursor-pointer">{t('mfa.manualKey')}</summary>
          <code className="mt-2 block break-all rounded bg-surface-2 p-2 font-mono text-ink">{setup.data.secret}</code>
        </details>
      )}
      <Field label={t('password.current')} hint={t('mfa.passwordHint')}>
        {(id) => <input id={id} type="password" className="input" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />}
      </Field>
      <Field label={t('mfa.code')}>
        {(id) => (
          <input id={id} className="input tabular text-center text-lg tracking-widest" inputMode="numeric" autoComplete="one-time-code"
            maxLength={7} required value={code} onChange={(e) => setCode(e.target.value)} />
        )}
      </Field>
      <ErrorAlert message={error} />
      <Button type="submit" variant="primary" loading={enable.isPending} disabled={!setup.data}>{t('mfa.enable')}</Button>
    </form>
  )
}

function DisableForm({ onDone }: { onDone: () => void }) {
  const disable = useMfaDisable()
  const { t } = useI18n()
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    try {
      await disable.mutateAsync({ password, code })
      onDone()
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-3">
      <p className="text-sm text-ink-2">{t('mfa.disableHelp')}</p>
      <Field label={t('login.password')}>
        {(id) => <input id={id} type="password" className="input" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />}
      </Field>
      <Field label={t('mfa.codeOrRecovery')}>
        {(id) => <input id={id} className="input" autoComplete="one-time-code" required value={code} onChange={(e) => setCode(e.target.value)} />}
      </Field>
      <ErrorAlert message={error} />
      <Button type="submit" variant="danger" loading={disable.isPending}>{t('mfa.disable')}</Button>
    </form>
  )
}

function RegenerateForm({ onDone }: { onDone: (codes: string[]) => void }) {
  const regenerate = useRegenerateRecoveryCodes()
  const { t } = useI18n()
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    try {
      const res = await regenerate.mutateAsync(code.replace(/\s/g, ''))
      onDone(res.recoveryCodes)
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-3">
      <p className="text-sm text-ink-2">{t('mfa.regenerateHelp')}</p>
      <Field label={t('mfa.appCode')}>
        {(id) => <input id={id} className="input tabular" inputMode="numeric" autoComplete="one-time-code" required value={code} onChange={(e) => setCode(e.target.value)} />}
      </Field>
      <ErrorAlert message={error} />
      <Button type="submit" variant="primary" loading={regenerate.isPending}>{t('mfa.generate')}</Button>
    </form>
  )
}

function RecoveryCodes({ codes }: { codes: string[] }) {
  const [copied, setCopied] = useState(false)
  const { t } = useI18n()
  return (
    <div className="flex flex-col gap-3">
      <p className="text-sm text-ink-2">
        {t('mfa.codesHelp')}
      </p>
      <ul className="grid grid-cols-2 gap-2 rounded-lg bg-surface-2 p-3 font-mono text-sm">
        {codes.map((c) => <li key={c}>{c}</li>)}
      </ul>
      <Button onClick={async () => { await navigator.clipboard.writeText(codes.join('\n')); setCopied(true) }}>
        {copied ? t('mfa.copied') : t('mfa.copy')}
      </Button>
    </div>
  )
}
