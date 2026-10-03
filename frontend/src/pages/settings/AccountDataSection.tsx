import { useState, type FormEvent } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { Download, Trash2 } from 'lucide-react'
import { clearCsrf, download, errorMessage, post } from '../../api/client'
import { useMe } from '../../api/hooks'
import { Button, ErrorAlert, Field, Modal } from '../../components/ui'
import { useI18n } from '../../i18n'

/** All of the user's data as a ZIP, and deleting the account with all of it. */
export function AccountDataSection() {
  const { t } = useI18n()
  const [exporting, setExporting] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function exportData() {
    setError(null)
    setExporting(true)
    try {
      await download('/api/account/export', 'finanze.zip')
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setExporting(false)
    }
  }

  return (
    <div className="flex flex-col gap-3">
      <p className="text-sm text-ink-2">{t('accountData.exportHelp')}</p>
      <ErrorAlert message={error} />
      <div>
        <Button onClick={exportData} loading={exporting}>
          <Download className="size-4" aria-hidden /> {t('accountData.export')}
        </Button>
      </div>
      <div className="mt-2 flex flex-col gap-3 border-t border-line pt-4">
        <p className="text-sm text-ink-2">{t('accountData.deleteHelp')}</p>
        <div>
          <Button variant="danger" onClick={() => setDeleting(true)}>
            <Trash2 className="size-4" aria-hidden /> {t('accountData.delete')}
          </Button>
        </div>
      </div>
      <Modal title={t('accountData.deleteTitle')} open={deleting} onClose={() => setDeleting(false)}>
        {deleting && <DeleteForm />}
      </Modal>
    </div>
  )
}

/** Password, the 2FA code when it is on, and the username typed again, so nobody deletes by mistake. */
function DeleteForm() {
  const me = useMe().data
  const qc = useQueryClient()
  const navigate = useNavigate()
  const { t } = useI18n()
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [username, setUsername] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  if (!me) return null
  const confirmed = username.trim().toLowerCase() === me.username

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!confirmed) return
    setError(null)
    setBusy(true)
    try {
      await post('/api/account/delete', me?.mfaEnabled ? { password, code } : { password })
    } catch (err) {
      setError(errorMessage(err))
      setBusy(false)
      return
    }
    clearCsrf()
    qc.clear()
    navigate('/login', { replace: true, state: { accountDeleted: true } })
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-3">
      <p className="text-sm text-ink-2">{t('accountData.deleteWarning')}</p>
      <Field label={t('login.password')}>
        {(id) => <input id={id} type="password" className="input" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />}
      </Field>
      {me.mfaEnabled && (
        <Field label={t('mfa.codeOrRecovery')}>
          {(id) => <input id={id} className="input" autoComplete="one-time-code" required value={code} onChange={(e) => setCode(e.target.value)} />}
        </Field>
      )}
      <Field label={t('accountData.typeUsername', { username: me.username })}>
        {(id) => <input id={id} className="input" autoComplete="off" autoCapitalize="none" spellCheck={false} required value={username} onChange={(e) => setUsername(e.target.value)} />}
      </Field>
      <ErrorAlert message={error} />
      <Button type="submit" variant="danger" loading={busy} disabled={!confirmed}>{t('accountData.deleteConfirm')}</Button>
    </form>
  )
}
