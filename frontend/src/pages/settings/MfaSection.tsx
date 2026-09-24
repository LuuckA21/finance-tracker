import { useEffect, useState, type FormEvent } from 'react'
import QRCode from 'qrcode'
import { ShieldCheck, ShieldOff } from 'lucide-react'
import { errorMessage } from '../../api/client'
import { useMe, useMfaDisable, useMfaEnable, useMfaSetup, useRegenerateRecoveryCodes } from '../../api/hooks'
import { Badge, Button, ErrorAlert, Field, Modal } from '../../components/ui'

/** Two-factor authentication: enrolment with QR code, recovery codes, disabling. */
export function MfaSection() {
  const me = useMe().data
  const [mode, setMode] = useState<'setup' | 'disable' | 'regenerate' | null>(null)
  const [codes, setCodes] = useState<string[] | null>(null)

  if (!me) return null
  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-2">
        {me.mfaEnabled ? <ShieldCheck className="size-5 text-good" /> : <ShieldOff className="size-5 text-muted" />}
        <span className="text-sm">
          {me.mfaEnabled ? 'Attiva' : 'Non attiva'}
        </span>
        {me.mfaEnabled && (
          <Badge tone={me.recoveryCodesRemaining < 3 ? 'bad' : 'neutral'}>{me.recoveryCodesRemaining} codici di recupero</Badge>
        )}
      </div>
      <p className="text-sm text-ink-2">
        Oltre alla password viene chiesto un codice a 6 cifre generato da un'app come Aegis, 2FAS, Google Authenticator o 1Password.
        Fortemente consigliata per un'app che contiene dati finanziari.
      </p>
      <div className="flex flex-wrap gap-2">
        {me.mfaEnabled ? (
          <>
            <Button onClick={() => setMode('regenerate')}>Nuovi codici di recupero</Button>
            <Button variant="danger" onClick={() => setMode('disable')}>Disattiva</Button>
          </>
        ) : (
          <Button variant="primary" onClick={() => setMode('setup')}>Attiva la verifica in due passaggi</Button>
        )}
      </div>

      <Modal title="Attiva la verifica in due passaggi" open={mode === 'setup'} onClose={() => setMode(null)}>
        {mode === 'setup' && <SetupFlow onEnabled={(c) => { setMode(null); setCodes(c) }} />}
      </Modal>
      <Modal title="Disattiva la verifica in due passaggi" open={mode === 'disable'} onClose={() => setMode(null)}>
        {mode === 'disable' && <DisableForm onDone={() => setMode(null)} />}
      </Modal>
      <Modal title="Nuovi codici di recupero" open={mode === 'regenerate'} onClose={() => setMode(null)}>
        {mode === 'regenerate' && <RegenerateForm onDone={(c) => { setMode(null); setCodes(c) }} />}
      </Modal>
      <Modal title="Codici di recupero" open={codes !== null} onClose={() => setCodes(null)}
        footer={<Button variant="primary" onClick={() => setCodes(null)}>Li ho salvati</Button>}>
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
  const [error, setError] = useState<string | null>(null)
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
      const res = await enable.mutateAsync(code.replace(/\s/g, ''))
      onEnabled(res.recoveryCodes)
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4">
      <ol className="list-decimal space-y-1 pl-5 text-sm text-ink-2">
        <li>Scansiona il QR code con la tua app di autenticazione.</li>
        <li>Inserisci il codice a 6 cifre che l'app mostra.</li>
      </ol>
      <div className="flex justify-center">
        {qr ? <img src={qr} alt="QR code per l'app di autenticazione" className="rounded-lg bg-white p-2" width={200} height={200} /> :
          <div className="size-[200px] animate-pulse rounded-lg bg-surface-2" />}
      </div>
      {setup.data && (
        <details className="text-xs text-ink-2">
          <summary className="cursor-pointer">Non riesci a scansionare? Inserisci la chiave a mano</summary>
          <code className="mt-2 block break-all rounded bg-surface-2 p-2 font-mono text-ink">{setup.data.secret}</code>
        </details>
      )}
      <Field label="Codice a 6 cifre">
        {(id) => (
          <input id={id} className="input tabular text-center text-lg tracking-widest" inputMode="numeric" autoComplete="one-time-code"
            maxLength={7} required value={code} onChange={(e) => setCode(e.target.value)} />
        )}
      </Field>
      <ErrorAlert message={error} />
      <Button type="submit" variant="primary" loading={enable.isPending} disabled={!setup.data}>Attiva</Button>
    </form>
  )
}

function DisableForm({ onDone }: { onDone: () => void }) {
  const disable = useMfaDisable()
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
      <p className="text-sm text-ink-2">Il tuo account resterà protetto solo dalla password.</p>
      <Field label="Password">
        {(id) => <input id={id} type="password" className="input" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />}
      </Field>
      <Field label="Codice dell'app o codice di recupero">
        {(id) => <input id={id} className="input" autoComplete="one-time-code" required value={code} onChange={(e) => setCode(e.target.value)} />}
      </Field>
      <ErrorAlert message={error} />
      <Button type="submit" variant="danger" loading={disable.isPending}>Disattiva</Button>
    </form>
  )
}

function RegenerateForm({ onDone }: { onDone: (codes: string[]) => void }) {
  const regenerate = useRegenerateRecoveryCodes()
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
      <p className="text-sm text-ink-2">I codici attuali smetteranno di funzionare.</p>
      <Field label="Codice a 6 cifre dall'app">
        {(id) => <input id={id} className="input tabular" inputMode="numeric" autoComplete="one-time-code" required value={code} onChange={(e) => setCode(e.target.value)} />}
      </Field>
      <ErrorAlert message={error} />
      <Button type="submit" variant="primary" loading={regenerate.isPending}>Genera</Button>
    </form>
  )
}

function RecoveryCodes({ codes }: { codes: string[] }) {
  const [copied, setCopied] = useState(false)
  return (
    <div className="flex flex-col gap-3">
      <p className="text-sm text-ink-2">
        Conservali in un posto sicuro (es. password manager). Ognuno funziona una sola volta se perdi l'accesso all'app.
        Non verranno più mostrati.
      </p>
      <ul className="grid grid-cols-2 gap-2 rounded-lg bg-surface-2 p-3 font-mono text-sm">
        {codes.map((c) => <li key={c}>{c}</li>)}
      </ul>
      <Button onClick={async () => { await navigator.clipboard.writeText(codes.join('\n')); setCopied(true) }}>
        {copied ? 'Copiati' : 'Copia negli appunti'}
      </Button>
    </div>
  )
}
