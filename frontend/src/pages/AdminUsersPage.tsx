import { useState, type FormEvent } from 'react'
import { Plus } from 'lucide-react'
import { errorMessage } from '../api/client'
import { useAdminAction, useAdminUsers, useMe, type AdminActionInput } from '../api/hooks'
import type { AdminUser, Role, UserWithPassword } from '../api/types'
import { Badge, Button, Card, ErrorAlert, Field, Modal, PageHeader, Spinner } from '../components/ui'
import { LANGUAGES, LANGUAGE_NAMES, useI18n, type Language } from '../i18n'
import { dateTime } from '../lib/format'

export function AdminUsersPage() {
  const users = useAdminUsers()
  const me = useMe().data
  const action = useAdminAction()
  const [createOpen, setCreateOpen] = useState(false)
  const [secret, setSecret] = useState<{ username: string; password: string } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const { t } = useI18n()

  async function run(input: AdminActionInput, confirmText?: string) {
    if (confirmText && !confirm(confirmText)) return
    setError(null)
    try {
      const res = await action.mutateAsync(input)
      if (res && 'temporaryPassword' in res && res.temporaryPassword) {
        setSecret({ username: res.user.username, password: res.temporaryPassword })
      }
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  return (
    <>
      <PageHeader
        title={t('nav.users')}
        subtitle={t('admin.subtitle')}
        actions={<Button variant="primary" onClick={() => setCreateOpen(true)}><Plus className="size-4" /> {t('admin.newUser')}</Button>}
      />
      <ErrorAlert message={error} />
      <Card className="mt-2">
        {users.isPending ? <Spinner /> : (
          <div className="-mx-4 overflow-x-auto sm:mx-0">
            <table className="w-full min-w-[44rem] text-sm">
              <thead>
                <tr className="border-b border-line text-left text-xs text-muted">
                  <th className="px-4 py-2 font-medium sm:px-2">{t('admin.colUser')}</th>
                  <th className="px-2 py-2 font-medium">{t('admin.colStatus')}</th>
                  <th className="px-2 py-2 font-medium">{t('admin.colLastLogin')}</th>
                  <th className="px-2 py-2 text-right font-medium">{t('admin.colActions')}</th>
                </tr>
              </thead>
              <tbody>
                {(users.data ?? []).map((u) => (
                  <UserRow key={u.id} u={u} self={u.id === me?.id} run={run} />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Modal title={t('admin.newUser')} open={createOpen} onClose={() => setCreateOpen(false)}>
        {createOpen && (
          <CreateUserForm onCreated={(res) => {
            setCreateOpen(false)
            if (res.temporaryPassword) setSecret({ username: res.user.username, password: res.temporaryPassword })
          }} />
        )}
      </Modal>

      <Modal title={t('admin.temporaryPassword')} open={secret !== null} onClose={() => setSecret(null)}
        footer={<Button variant="primary" onClick={() => setSecret(null)}>{t('admin.done')}</Button>}>
        {secret && (
          <div className="flex flex-col gap-3 text-sm">
            <p className="text-ink-2">
              {t('admin.shareBefore')} <strong className="text-ink">{secret.username}</strong> {t('admin.shareAfter')}
            </p>
            <code className="select-all rounded-lg bg-surface-2 p-3 text-center font-mono text-base">{secret.password}</code>
            <Button onClick={() => navigator.clipboard.writeText(secret.password)}>{t('admin.copy')}</Button>
          </div>
        )}
      </Modal>
    </>
  )
}

function UserRow({ u, self, run }: { u: AdminUser; self: boolean; run: (a: AdminActionInput, c?: string) => void }) {
  const { t } = useI18n()
  return (
    <tr className="border-b border-line last:border-0 align-top">
      <td className="px-4 py-2.5 sm:px-2">
        <p className="font-medium">{u.username} {self && <span className="text-xs font-normal text-muted">{t('admin.you')}</span>}</p>
        <p className="text-xs text-muted">{u.role === 'ADMIN' ? t('admin.roleADMIN') : t('admin.roleUSER')}</p>
      </td>
      <td className="px-2 py-2.5">
        <div className="flex flex-wrap gap-1">
          {u.enabled ? <Badge tone="good">{t('admin.active')}</Badge> : <Badge tone="bad">{t('admin.inactive')}</Badge>}
          {u.locked && <Badge tone="bad">{t('admin.locked')}</Badge>}
          {u.mfaEnabled && <Badge tone="accent">2FA</Badge>}
          {u.passwordChangeRequired && <Badge>{t('admin.tempPasswordBadge')}</Badge>}
        </div>
      </td>
      <td className="px-2 py-2.5 text-ink-2">{dateTime(u.lastLoginAt)}</td>
      <td className="px-2 py-2.5">
        <div className="flex flex-wrap justify-end gap-1">
          {u.locked && <Button variant="ghost" onClick={() => run({ type: 'unlock', id: u.id })}>{t('admin.unlock')}</Button>}
          <Button variant="ghost" onClick={() => run({ type: 'reset-password', id: u.id },
            t('admin.confirmResetPassword', { name: u.username }))}>{t('admin.resetPassword')}</Button>
          {u.mfaEnabled && (
            <Button variant="ghost" onClick={() => run({ type: 'reset-mfa', id: u.id },
              t('admin.confirmResetMfa', { name: u.username }))}>{t('admin.resetMfa')}</Button>
          )}
          {!self && (
            <>
              <Button variant="ghost" onClick={() => run({ type: 'update', id: u.id, body: { role: u.role === 'ADMIN' ? 'USER' : 'ADMIN' } })}>
                {u.role === 'ADMIN' ? t('admin.makeUser') : t('admin.makeAdmin')}
              </Button>
              <Button variant="ghost" onClick={() => run({ type: 'update', id: u.id, body: { enabled: !u.enabled } })}>
                {u.enabled ? t('admin.disable') : t('admin.enable')}
              </Button>
              <Button variant="danger" onClick={() => run({ type: 'delete', id: u.id },
                t('admin.confirmDelete', { name: u.username }))}>{t('common.delete')}</Button>
            </>
          )}
        </div>
      </td>
    </tr>
  )
}

function CreateUserForm({ onCreated }: { onCreated: (res: UserWithPassword) => void }) {
  const action = useAdminAction()
  const [username, setUsername] = useState('')
  const [role, setRole] = useState<Role>('USER')
  const [error, setError] = useState<string | null>(null)
  const { t, language: current } = useI18n()
  const [language, setLanguage] = useState<Language>(current)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    try {
      const res = await action.mutateAsync({ type: 'create', body: { username: username.trim().toLowerCase(), role, language } })
      onCreated(res as UserWithPassword)
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-3">
      <Field label={t('login.username')} hint={t('admin.usernameHint')}>
        {(id) => <input id={id} className="input" required minLength={3} maxLength={64} autoCapitalize="none" autoFocus
          value={username} onChange={(e) => setUsername(e.target.value)} />}
      </Field>
      <Field label={t('admin.role')}>
        {(id) => (
          <select id={id} className="input" value={role} onChange={(e) => setRole(e.target.value as Role)}>
            <option value="USER">{t('admin.roleUSER')}</option>
            <option value="ADMIN">{t('admin.roleADMIN')}</option>
          </select>
        )}
      </Field>
      <Field label={t('settings.language')} hint={t('admin.languageHint')}>
        {(id) => (
          <select id={id} className="input" value={language} onChange={(e) => setLanguage(e.target.value as Language)}>
            {LANGUAGES.map((l) => <option key={l} value={l}>{LANGUAGE_NAMES[l]}</option>)}
          </select>
        )}
      </Field>
      <p className="text-xs text-muted">{t('admin.createHelp')}</p>
      <ErrorAlert message={error} />
      <div className="flex justify-end"><Button type="submit" variant="primary" loading={action.isPending}>{t('admin.create')}</Button></div>
    </form>
  )
}
