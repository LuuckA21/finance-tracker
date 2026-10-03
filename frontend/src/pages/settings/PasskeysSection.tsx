import { useState, type FormEvent } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Cloud, Fingerprint, Pencil, Trash2 } from 'lucide-react'
import { errorMessage, post } from '../../api/client'
import { useDeletePasskey, useMe, usePasskeys, useRenamePasskey } from '../../api/hooks'
import type { Passkey } from '../../api/types'
import { Badge, Button, ErrorAlert, Field, Modal, Spinner } from '../../components/ui'
import { useI18n } from '../../i18n'
import { dateTime } from '../../lib/format'
import { createPasskey, isCancelled, passkeysSupported, type CreationOptionsJSON } from '../../lib/webauthn'

/** The user's passkeys: sign in with the device's lock instead of password and code. */
export function PasskeysSection() {
  const passkeys = usePasskeys()
  const remove = useDeletePasskey()
  const [adding, setAdding] = useState(false)
  const [renaming, setRenaming] = useState<Passkey | null>(null)
  const [error, setError] = useState<string | null>(null)
  const { t } = useI18n()
  const supported = passkeysSupported()

  async function onDelete(passkey: Passkey) {
    if (!confirm(t('passkey.confirmDelete', { name: passkey.name }))) return
    setError(null)
    try {
      await remove.mutateAsync(passkey.id)
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  if (passkeys.isPending) return <Spinner />
  const list = passkeys.data ?? []
  return (
    <div className="flex flex-col gap-3">
      <p className="text-sm text-ink-2">{t('passkey.help')}</p>
      <ErrorAlert message={error} />
      {list.length > 0 && (
        <ul className="divide-y divide-line">
          {list.map((p) => (
            <li key={p.id} className="flex items-center justify-between gap-3 py-2 text-sm">
              <span className="flex min-w-0 items-center gap-2">
                <Fingerprint className="size-4 shrink-0 text-muted" aria-hidden />
                <span className="min-w-0">
                  <span className="flex flex-wrap items-center gap-2">
                    <span className="truncate font-medium">{p.name}</span>
                    {p.synced && <Badge tone="neutral"><Cloud className="mr-1 inline size-3" aria-hidden />{t('passkey.synced')}</Badge>}
                  </span>
                  <span className="block text-xs text-muted">
                    {t('passkey.created', { date: dateTime(p.createdAt) })}
                    {' · '}
                    {p.lastUsedAt ? t('passkey.lastUsed', { date: dateTime(p.lastUsedAt) }) : t('passkey.neverUsed')}
                  </span>
                </span>
              </span>
              <span className="flex shrink-0 gap-1">
                <button type="button" className="rounded p-1.5 text-muted hover:text-ink" aria-label={t('passkey.rename', { name: p.name })}
                  onClick={() => setRenaming(p)}>
                  <Pencil className="size-4" />
                </button>
                <button type="button" className="rounded p-1.5 text-muted hover:text-bad" aria-label={t('passkey.delete', { name: p.name })}
                  onClick={() => onDelete(p)}>
                  <Trash2 className="size-4" />
                </button>
              </span>
            </li>
          ))}
        </ul>
      )}
      {supported ? (
        <div><Button variant="primary" onClick={() => setAdding(true)}>{t('passkey.add')}</Button></div>
      ) : (
        <p className="text-sm text-muted">{t('passkey.unsupported')}</p>
      )}

      <Modal title={t('passkey.add')} open={adding} onClose={() => setAdding(false)}>
        {adding && <AddForm onDone={() => setAdding(false)} />}
      </Modal>
      <Modal title={t('passkey.renameTitle')} open={renaming !== null} onClose={() => setRenaming(null)}>
        {renaming && <RenameForm passkey={renaming} onDone={() => setRenaming(null)} />}
      </Modal>
    </div>
  )
}

/** A name for the passkey and, before the device's dialog, the password (and code with 2FA on). */
function AddForm({ onDone }: { onDone: () => void }) {
  const me = useMe().data
  const qc = useQueryClient()
  const { t } = useI18n()
  const [name, setName] = useState(defaultName())
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setBusy(true)
    try {
      const options = await post<CreationOptionsJSON>('/api/account/passkeys/options',
        me?.mfaEnabled ? { password, code } : { password })
      const credential = await createPasskey(options)
      await post('/api/account/passkeys', { name, credential })
      await qc.invalidateQueries({ queryKey: ['passkeys'] })
      onDone()
    } catch (err) {
      setError(isCancelled(err) ? t('passkey.cancelled') : errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-3">
      <p className="text-sm text-ink-2">{t('passkey.addHelp')}</p>
      <Field label={t('passkey.name')} hint={t('passkey.nameHint')}>
        {(id) => <input id={id} className="input" required maxLength={64} value={name} onChange={(e) => setName(e.target.value)} />}
      </Field>
      <Field label={t('login.password')}>
        {(id) => <input id={id} type="password" className="input" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />}
      </Field>
      {me?.mfaEnabled && (
        <Field label={t('mfa.codeOrRecovery')}>
          {(id) => <input id={id} className="input" autoComplete="one-time-code" required value={code} onChange={(e) => setCode(e.target.value)} />}
        </Field>
      )}
      <ErrorAlert message={error} />
      <Button type="submit" variant="primary" loading={busy}>{t('passkey.create')}</Button>
    </form>
  )
}

function RenameForm({ passkey, onDone }: { passkey: Passkey; onDone: () => void }) {
  const rename = useRenamePasskey()
  const { t } = useI18n()
  const [name, setName] = useState(passkey.name)
  const [error, setError] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    try {
      await rename.mutateAsync({ id: passkey.id, name })
      onDone()
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-3">
      <Field label={t('passkey.name')}>
        {(id) => <input id={id} className="input" required maxLength={64} autoFocus value={name} onChange={(e) => setName(e.target.value)} />}
      </Field>
      <ErrorAlert message={error} />
      <Button type="submit" variant="primary" loading={rename.isPending}>{t('common.save')}</Button>
    </form>
  )
}

/** A starting name from the device the browser runs on, to tell passkeys apart. */
function defaultName(): string {
  const agent = navigator.userAgent
  const device = /iPhone/.test(agent) ? 'iPhone' : /iPad/.test(agent) ? 'iPad' : /Android/.test(agent) ? 'Android'
    : /Mac OS X/.test(agent) ? 'Mac' : /Windows/.test(agent) ? 'Windows' : /Linux/.test(agent) ? 'Linux' : ''
  return device
}
