import { createHmac, randomBytes } from 'node:crypto'
import { test as base, expect, request as playwrightRequest, type APIRequestContext, type Page } from '@playwright/test'

/** Password of every user the tests create (long and uncommon, as the backend requires). */
export const PASSWORD = 'Quiet-Harbor-Lantern-e2e-47'

export type Language = 'IT' | 'EN' | 'DE' | 'FR'

export interface User {
  username: string
  password: string
}

/** The bootstrap administrator (global-setup.ts may have replaced its first-login password). */
export function adminUser(): User {
  return {
    username: process.env.E2E_ADMIN_USERNAME ?? 'admin',
    password: process.env.E2E_ADMIN_PASSWORD ?? 'dev-password-change-me',
  }
}

/** Calls the API like the app does: session cookie plus the CSRF token on writes. */
export async function api(request: APIRequestContext, method: string, url: string, data?: unknown) {
  const headers: Record<string, string> = {}
  if (method !== 'GET') {
    const csrf = await request.get('/api/auth/csrf')
    expect(csrf.ok(), 'CSRF token').toBeTruthy()
    headers['X-CSRF-TOKEN'] = ((await csrf.json()) as { token: string }).token
  }
  return request.fetch(url, { method, data, headers })
}

/** Like {@link api}, but fails the test unless the call succeeds, and returns the JSON body. */
export async function apiOk<T = unknown>(request: APIRequestContext, method: string, url: string, data?: unknown): Promise<T> {
  const res = await api(request, method, url, data)
  expect(res.ok(), `${method} ${url}: ${res.status()} ${await res.text()}`).toBeTruthy()
  return (res.status() === 204 ? undefined : await res.json()) as T
}

export async function apiLogin(request: APIRequestContext, user: User) {
  await apiOk(request, 'POST', '/api/auth/login', { username: user.username, password: user.password })
}

export function uniqueName(prefix: string) {
  return `${prefix}-${Date.now().toString(36)}-${randomBytes(3).toString('hex')}`
}

/** Current TOTP code (RFC 6238, SHA-1, 30 s) of a base32 secret; `offset` moves by time steps. */
export function totp(secret: string, offset = 0) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
  let bits = ''
  for (const c of secret.replace(/[\s=]/g, '').toUpperCase()) bits += alphabet.indexOf(c).toString(2).padStart(5, '0')
  const key = Buffer.from(bits.match(/.{8}/g)!.map((b) => parseInt(b, 2)))
  const message = Buffer.alloc(8)
  message.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30_000) + offset))
  const hmac = createHmac('sha1', key).update(message).digest()
  const o = hmac[hmac.length - 1] & 15
  const code = (((hmac[o] & 127) << 24) | (hmac[o + 1] << 16) | (hmac[o + 2] << 8) | hmac[o + 3]) % 1_000_000
  return String(code).padStart(6, '0')
}

/** Today in the browser's time zone (Europe/Zurich), as the app's date inputs expect it. */
export function today() {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Europe/Zurich' }).format(new Date())
}

/** Fails when the page scrolls sideways, i.e. something is wider than the viewport. */
export async function expectNoHorizontalScroll(page: Page) {
  const { scrollWidth, clientWidth } = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }))
  expect(scrollWidth, `page is ${scrollWidth}px wide in a ${clientWidth}px viewport`).toBeLessThanOrEqual(clientWidth)
}

interface Fixtures {
  /** Interface language of the user created for the test. */
  language: Language
  /** A fresh user (password already changed) owned by this test only, deleted afterwards. */
  user: User
  /** The browser page, already signed in as `user`. */
  signedIn: Page
}

interface WorkerFixtures {
  /** API session of the bootstrap administrator, shared by the tests of a worker. */
  admin: APIRequestContext
}

export const test = base.extend<Fixtures, WorkerFixtures>({
  admin: [async ({}, use, workerInfo) => {
    const request = await playwrightRequest.newContext({ baseURL: workerInfo.project.use.baseURL })
    await apiLogin(request, adminUser())
    await use(request)
    await request.dispose()
  }, { scope: 'worker' }],

  language: ['IT', { option: true }],

  user: async ({ admin, language, baseURL }, use) => {
    const username = uniqueName('e2e')
    const created = await apiOk<{ user: { id: number }; temporaryPassword: string }>(admin, 'POST', '/api/admin/users',
      { username, role: 'USER', language })
    const request = await playwrightRequest.newContext({ baseURL })
    await apiLogin(request, { username, password: created.temporaryPassword })
    await apiOk(request, 'PUT', '/api/account/password', { currentPassword: created.temporaryPassword, newPassword: PASSWORD })
    await request.dispose()
    await use({ username, password: PASSWORD })
    // Deleting the user removes all of its data: the database stays as it was
    await apiOk(admin, 'DELETE', `/api/admin/users/${created.user.id}`)
  },

  signedIn: async ({ page, user }, use) => {
    // page.request shares the browser context's cookies: the page is signed in too
    await apiLogin(page.request, user)
    const errors: string[] = []
    page.on('pageerror', (e) => errors.push(e.message))
    await use(page)
    expect(errors, 'uncaught errors in the page').toEqual([])
  },
})

export { expect }
