/**
 * Thin fetch wrapper for the same-origin API.
 *
 * - The session lives in an HttpOnly cookie the browser sends automatically.
 * - Mutating requests carry the CSRF token (kept in memory only, never in storage).
 * - A 403 on a mutating request may mean the token rotated: refresh it once and retry.
 */

import { hasMessage, translate, type MessageKey } from '../i18n'

export class ApiError extends Error {
  readonly status: number
  readonly code: string | undefined
  readonly fieldErrors: Record<string, string> | undefined
  /** CSV import: index of the confirmed row that was rejected */
  readonly row: number | undefined
  /** CSV import: line of the file that could not be read */
  readonly line: number | undefined
  /** CSV import: required columns missing from the header */
  readonly columns: string[] | undefined

  constructor(status: number, message: string, code?: string, fieldErrors?: Record<string, string>,
    extra: { row?: number; line?: number; columns?: string[] } = {}) {
    super(message)
    this.status = status
    this.code = code
    this.fieldErrors = fieldErrors
    this.row = extra.row
    this.line = extra.line
    this.columns = extra.columns
  }
}

let csrfToken: string | null = null
let csrfPromise: Promise<string> | null = null

export async function refreshCsrf(): Promise<string> {
  if (!csrfPromise) {
    csrfPromise = fetch('/api/auth/csrf', { credentials: 'same-origin' })
      .then(async (res) => {
        if (!res.ok) throw new ApiError(res.status, translate('login.csrfFailed'))
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
  // FormData (file upload): the browser sets the multipart boundary itself
  const isForm = body instanceof FormData
  if (body !== undefined && !isForm) headers['Content-Type'] = 'application/json'
  if (method !== 'GET') {
    headers['X-CSRF-TOKEN'] = csrfToken ?? (await refreshCsrf())
  }
  const res = await fetch(url, {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined ? undefined : isForm ? body : JSON.stringify(body),
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
  let message = translate('common.httpError', { status: res.status })
  let code: string | undefined
  let fieldErrors: Record<string, string> | undefined
  let extra: { row?: number; line?: number; columns?: string[] } = {}
  try {
    const data = (await res.json()) as {
      detail?: string; code?: string; errors?: Record<string, string>; row?: number; line?: number; columns?: string[]
    }
    message = data.detail ?? message
    code = data.code
    fieldErrors = data.errors
    extra = {
      row: typeof data.row === 'number' ? data.row : undefined,
      line: typeof data.line === 'number' && data.line > 0 ? data.line : undefined,
      columns: Array.isArray(data.columns) ? data.columns.filter((c) => typeof c === 'string') : undefined,
    }
  } catch {
    // body was not JSON
  }
  return new ApiError(res.status, message, code, fieldErrors, extra)
}

/** Listeners notified when the server says the session is gone (401). */
const unauthorizedListeners = new Set<() => void>()
export function onUnauthorized(listener: () => void) {
  unauthorizedListeners.add(listener)
  return () => {
    unauthorizedListeners.delete(listener)
  }
}

async function api<T>(method: Method, url: string, body?: unknown): Promise<T> {
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
export const del = <T = void>(url: string) => api<T>('DELETE', url)
export const upload = <T>(url: string, form: FormData) => api<T>('POST', url, form)

/**
 * Downloads a file from the API (same session and error handling as other calls) and hands it
 * to the browser under the name the server suggests.
 */
export async function download(url: string, fallbackName: string): Promise<void> {
  const res = await send('GET', url, undefined, false)
  if (!res.ok) {
    const error = await toError(res)
    if (res.status === 401) unauthorizedListeners.forEach((l) => l())
    throw error
  }
  const match = /filename="([^"]+)"/.exec(res.headers.get('Content-Disposition') ?? '')
  saveBlob(await res.blob(), match?.[1] ?? fallbackName)
}

/** Offers a blob as a file download. */
export function saveBlob(blob: Blob, name: string) {
  const href = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = href
  link.download = name
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(href), 1000)
}

/** Human readable message for any thrown value, in the current language. */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return translateCode(error.code) ?? error.message
  }
  if (error instanceof Error) return error.message
  return translate('common.unexpectedError')
}

function translateCode(code: string | undefined): string | undefined {
  const key = `error.${code}` as MessageKey
  return code && hasMessage(key) ? translate(key) : undefined
}
