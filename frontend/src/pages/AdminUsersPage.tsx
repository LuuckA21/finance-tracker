import { useState, type FormEvent } from 'react'
import { Plus } from 'lucide-react'
import { errorMessage } from '../api/client'
import { useAdminAction, useAdminUsers, useMe, type AdminActionInput } from '../api/hooks'
import type { AdminUser, Role, UserWithPassword } from '../api/types'
import { Badge, Button, Card, ErrorAlert, Field, Modal, PageHeader, Spinner } from '../components/ui'
import { dateTime } from '../lib/format'

export function AdminUsersPage() {
  const users = useAdminUsers()
  const me = useMe().data
  const action = useAdminAction()
  const [createOpen, setCreateOpen] = useState(false)
  const [secret, setSecret] = useState<{ username: string; password: string } | null>(null)
  const [error, setError] = useState<string | null>(null)

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
        title="Utenti"
        subtitle="Gli account si creano solo da qui. Gli amministratori gestiscono gli accessi ma non vedono i dati finanziari degli altri."
        actions={<Button variant="primary" onClick={() => setCreateOpen(true)}><Plus className="size-4" /> Nuovo utente</Button>}
      />
      <ErrorAlert message={error} />
      <Card className="mt-2">
        {users.isPending ? <Spinner /> : (
          <div className="-mx-4 overflow-x-auto sm:mx-0">
            <table className="w-full min-w-[44rem] text-sm">
              <thead>
                <tr className="border-b border-line text-left text-xs text-muted">
                  <th className="px-4 py-2 font-medium sm:px-2">Utente</th>
                  <th className="px-2 py-2 font-medium">Stato</th>
                  <th className="px-2 py-2 font-medium">Ultimo accesso</th>
                  <th className="px-2 py-2 text-right font-medium">Azioni</th>
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

      <Modal title="Nuovo utente" open={createOpen} onClose={() => setCreateOpen(false)}>
        {createOpen && (
          <CreateUserForm onCreated={(res) => {
            setCreateOpen(false)
            if (res.temporaryPassword) setSecret({ username: res.user.username, password: res.temporaryPassword })
          }} />
        )}
      </Modal>

      <Modal title="Password temporanea" open={secret !== null} onClose={() => setSecret(null)}
        footer={<Button variant="primary" onClick={() => setSecret(null)}>Fatto</Button>}>
        {secret && (
          <div className="flex flex-col gap-3 text-sm">
            <p className="text-ink-2">
              Comunica questa password a <strong className="text-ink">{secret.username}</strong> tramite un canale sicuro.
              Viene mostrata una sola volta e dovrà essere cambiata al primo accesso.
            </p>
            <code className="select-all rounded-lg bg-surface-2 p-3 text-center font-mono text-base">{secret.password}</code>
            <Button onClick={() => navigator.clipboard.writeText(secret.password)}>Copia</Button>
          </div>
        )}
      </Modal>
    </>
  )
}

function UserRow({ u, self, run }: { u: AdminUser; self: boolean; run: (a: AdminActionInput, c?: string) => void }) {
  return (
    <tr className="border-b border-line last:border-0 align-top">
      <td className="px-4 py-2.5 sm:px-2">
        <p className="font-medium">{u.username} {self && <span className="text-xs font-normal text-muted">(tu)</span>}</p>
        <p className="text-xs text-muted">{u.role === 'ADMIN' ? 'Amministratore' : 'Utente'}</p>
      </td>
      <td className="px-2 py-2.5">
        <div className="flex flex-wrap gap-1">
          {u.enabled ? <Badge tone="good">attivo</Badge> : <Badge tone="bad">disattivato</Badge>}
          {u.locked && <Badge tone="bad">bloccato</Badge>}
          {u.mfaEnabled && <Badge tone="accent">2FA</Badge>}
          {u.passwordChangeRequired && <Badge>password temporanea</Badge>}
        </div>
      </td>
      <td className="px-2 py-2.5 text-ink-2">{dateTime(u.lastLoginAt)}</td>
      <td className="px-2 py-2.5">
        <div className="flex flex-wrap justify-end gap-1">
          {u.locked && <Button variant="ghost" onClick={() => run({ type: 'unlock', id: u.id })}>Sblocca</Button>}
          <Button variant="ghost" onClick={() => run({ type: 'reset-password', id: u.id },
            `Generare una nuova password temporanea per ${u.username}? Le sue sessioni verranno chiuse.`)}>Reimposta password</Button>
          {u.mfaEnabled && (
            <Button variant="ghost" onClick={() => run({ type: 'reset-mfa', id: u.id },
              `Disattivare la 2FA di ${u.username}? Usalo solo se ha perso l'app e i codici di recupero.`)}>Reset 2FA</Button>
          )}
          {!self && (
            <>
              <Button variant="ghost" onClick={() => run({ type: 'update', id: u.id, body: { role: u.role === 'ADMIN' ? 'USER' : 'ADMIN' } })}>
                {u.role === 'ADMIN' ? 'Rendi utente' : 'Rendi admin'}
              </Button>
              <Button variant="ghost" onClick={() => run({ type: 'update', id: u.id, body: { enabled: !u.enabled } })}>
                {u.enabled ? 'Disattiva' : 'Riattiva'}
              </Button>
              <Button variant="danger" onClick={() => run({ type: 'delete', id: u.id },
                `Eliminare ${u.username} e TUTTI i suoi dati finanziari? L'operazione non si può annullare.`)}>Elimina</Button>
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

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    try {
      const res = await action.mutateAsync({ type: 'create', body: { username: username.trim().toLowerCase(), role } })
      onCreated(res as UserWithPassword)
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-3">
      <Field label="Nome utente" hint="Minuscole, cifre, “.”, “_” o “-”, da 3 a 64 caratteri.">
        {(id) => <input id={id} className="input" required minLength={3} maxLength={64} autoCapitalize="none" autoFocus
          value={username} onChange={(e) => setUsername(e.target.value)} />}
      </Field>
      <Field label="Ruolo">
        {(id) => (
          <select id={id} className="input" value={role} onChange={(e) => setRole(e.target.value as Role)}>
            <option value="USER">Utente</option>
            <option value="ADMIN">Amministratore</option>
          </select>
        )}
      </Field>
      <p className="text-xs text-muted">Verrà generata una password temporanea da cambiare al primo accesso.</p>
      <ErrorAlert message={error} />
      <div className="flex justify-end"><Button type="submit" variant="primary" loading={action.isPending}>Crea utente</Button></div>
    </form>
  )
}
