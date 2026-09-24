/**
 * Thin fetch wrapper for the same-origin API.
 *
 * - The session lives in an HttpOnly cookie the browser sends automatically.
 * - Mutating requests carry the CSRF token (kept in memory only, never in storage).
 * - A 403 on a mutating request may mean the token rotated: refresh it once and retry.
 */

export class ApiError extends Error {
  readonly status: number
  readonly code: string | undefined
  readonly fieldErrors: Record<string, string> | undefined

  constructor(status: number, message: string, code?: string, fieldErrors?: Record<string, string>) {
    super(message)
    this.status = status
    this.code = code
    this.fieldErrors = fieldErrors
  }
}

let csrfToken: string | null = null
let csrfPromise: Promise<string> | null = null

export async function refreshCsrf(): Promise<string> {
  if (!csrfPromise) {
    csrfPromise = fetch('/api/auth/csrf', { credentials: 'same-origin' })
      .then(async (res) => {
        if (!res.ok) throw new ApiError(res.status, 'Impossibile ottenere il token CSRF')
        const body = (await res.json()) as { token: string }
        csrfToken = body.token
        return body.token
      })
      .finally(() => {
        csrfPromise = null
      })
  }
  return csrfPromise
}

export function clearCsrf() {
  csrfToken = null
}

type Method = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'

async function send(method: Method, url: string, body: unknown, retry: boolean): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (method !== 'GET') {
    headers['X-CSRF-TOKEN'] = csrfToken ?? (await refreshCsrf())
  }
  const res = await fetch(url, {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (res.status === 403 && method !== 'GET' && retry) {
    const code = await peekCode(res.clone())
    if (!code) {
      // Plain 403 from the CSRF filter: rotate token and try once more
      await refreshCsrf()
      return send(method, url, body, false)
    }
  }
  return res
}

async function peekCode(res: Response): Promise<string | undefined> {
  try {
    const data = (await res.json()) as { code?: string }
    return data.code
  } catch {
    return undefined
  }
}

async function toError(res: Response): Promise<ApiError> {
  let message = `Errore ${res.status}`
  let code: string | undefined
  let fieldErrors: Record<string, string> | undefined
  try {
    const data = (await res.json()) as { detail?: string; code?: string; errors?: Record<string, string> }
    message = data.detail ?? message
    code = data.code
    fieldErrors = data.errors
  } catch {
    // body was not JSON
  }
  return new ApiError(res.status, message, code, fieldErrors)
}

/** Listeners notified when the server says the session is gone (401). */
const unauthorizedListeners = new Set<() => void>()
export function onUnauthorized(listener: () => void) {
  unauthorizedListeners.add(listener)
  return () => {
    unauthorizedListeners.delete(listener)
  }
}

export async function api<T>(method: Method, url: string, body?: unknown): Promise<T> {
  const res = await send(method, url, body, true)
  if (!res.ok) {
    const error = await toError(res)
    if (res.status === 401 && !url.startsWith('/api/auth/login')) {
      unauthorizedListeners.forEach((l) => l())
    }
    throw error
  }
  if (res.status === 204) return undefined as T
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

export const get = <T>(url: string) => api<T>('GET', url)
export const post = <T>(url: string, body?: unknown) => api<T>('POST', url, body ?? {})
export const put = <T>(url: string, body: unknown) => api<T>('PUT', url, body)
export const patch = <T>(url: string, body: unknown) => api<T>('PATCH', url, body)
export const del = (url: string) => api<void>('DELETE', url)

/** Human readable message for any thrown value. */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return translateCode(error.code) ?? error.message
  }
  if (error instanceof Error) return error.message
  return 'Errore inatteso'
}

const CODE_MESSAGES: Record<string, string> = {
  invalid_credentials: 'Nome utente o password non validi.',
  too_many_attempts: 'Troppi tentativi falliti. Riprova più tardi.',
  invalid_mfa_code: 'Codice non valido.',
  mfa_expired: 'Accesso scaduto, inserisci di nuovo le credenziali.',
  password_change_required: 'Devi prima cambiare la password.',
  invalid_current_password: 'La password attuale non è corretta.',
  weak_password: 'La nuova password non rispetta i requisiti (min. 12 caratteri, non comune, senza il nome utente).',
  password_reused: 'La nuova password deve essere diversa da quella attuale.',
  category_exists: 'Esiste già una categoria con questo nome.',
  category_in_use: 'La categoria è usata da alcuni movimenti e non può essere eliminata.',
  category_kind_mismatch: 'La categoria non corrisponde al tipo (entrata/uscita).',
  snapshot_exists: 'Esiste già una rilevazione per questa data.',
  base_currency_rate: 'Non serve un tasso per la valuta di base.',
  username_taken: 'Nome utente già in uso.',
  invalid_username: 'Nome utente non valido: 3-64 caratteri, minuscole, cifre, ".", "_" o "-".',
  cannot_modify_self: 'Non puoi disattivare, declassare o eliminare il tuo account.',
  last_admin: 'Serve almeno un amministratore attivo.',
  mfa_already_enabled: 'La verifica in due passaggi è già attiva.',
  mfa_not_enabled: 'La verifica in due passaggi non è attiva.',
  validation_failed: 'Controlla i campi evidenziati.',
  not_found: 'Elemento non trovato.',
  conflict: 'Operazione in conflitto con dati esistenti.',
}

function translateCode(code: string | undefined): string | undefined {
  return code ? CODE_MESSAGES[code] : undefined
}
