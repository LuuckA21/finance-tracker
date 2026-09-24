import { useState, type FormEvent } from 'react'
import { refreshCsrf, errorMessage } from '../../api/client'
import { useChangePassword } from '../../api/hooks'
import { Button, ErrorAlert, Field } from '../../components/ui'

export function ChangePasswordForm({ onDone }: { onDone?: () => void }) {
  const change = useChangePassword()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [ok, setOk] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setOk(false)
    if (next !== confirm) {
      setError('Le due password non coincidono.')
      return
    }
    if (next.length < 12) {
      setError('La password deve avere almeno 12 caratteri.')
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
      <Field label="Password attuale">
        {(id) => <input id={id} type="password" className="input" autoComplete="current-password" required value={current} onChange={(e) => setCurrent(e.target.value)} />}
      </Field>
      <Field label="Nuova password" hint="Almeno 12 caratteri. Una frase di più parole è facile da ricordare e sicura.">
        {(id) => <input id={id} type="password" className="input" autoComplete="new-password" required minLength={12} maxLength={128} value={next} onChange={(e) => setNext(e.target.value)} />}
      </Field>
      <Field label="Conferma nuova password">
        {(id) => <input id={id} type="password" className="input" autoComplete="new-password" required value={confirm} onChange={(e) => setConfirm(e.target.value)} />}
      </Field>
      <ErrorAlert message={error} />
      {ok && <p className="text-sm text-good">Password aggiornata. Le altre sessioni sono state disconnesse.</p>}
      <div>
        <Button type="submit" variant="primary" loading={change.isPending}>Cambia password</Button>
      </div>
    </form>
  )
}
