import { readFile, writeFile } from 'node:fs/promises'
import { expect, test } from './fixtures'

test.use({ serviceWorkers: 'allow' })
// One after the other: the update test changes the built service worker for a moment
test.describe.configure({ mode: 'default' })

const SERVICE_WORKER = new URL('../dist/sw.js', import.meta.url)

/** Paths of everything the service worker keeps, in all of its caches. */
function cachedPaths() {
  return async () => {
    const paths: string[] = []
    for (const name of await caches.keys()) {
      for (const request of await (await caches.open(name)).keys()) paths.push(new URL(request.url).pathname)
    }
    return paths
  }
}

test('installable app: manifest, icons, and a service worker that keeps the app but never the data', async ({ signedIn: page, context }) => {
  await page.goto('/')
  await expect(page.getByRole('navigation', { name: 'Navigazione principale' })).toBeVisible()

  // The manifest of the user's language makes it installable
  await expect(page.locator('link[rel="manifest"]')).toHaveAttribute('href', '/manifest-it.webmanifest')
  const manifest = await (await page.request.get('/manifest-it.webmanifest')).json() as {
    id: string; name: string; display: string; start_url: string
    icons: { src: string; purpose?: string }[]; shortcuts: { name: string; url: string }[]
  }
  expect(manifest).toMatchObject({ id: '/', name: 'Finanze', display: 'standalone', start_url: '/' })
  expect(manifest.icons.some((i) => i.purpose === 'maskable')).toBe(true)
  for (const icon of manifest.icons) expect((await page.request.get(icon.src)).ok(), icon.src).toBe(true)
  expect(manifest.shortcuts[0]).toEqual(expect.objectContaining({ name: 'Nuovo movimento', url: '/movimenti?new=1' }))

  // The service worker takes over and keeps the files of the build
  await page.evaluate(async () => { await navigator.serviceWorker.ready })
  await page.reload()
  expect(await page.evaluate(() => navigator.serviceWorker.controller !== null)).toBe(true)
  const kept = await page.evaluate(cachedPaths())
  expect(kept).toContain('/index.html')
  expect(kept.some((p) => p.startsWith('/assets/'))).toBe(true)

  // Data goes to the server every time and is never kept
  const me = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/auth/me')
  await page.reload()
  expect((await me).fromServiceWorker()).toBe(false)
  expect((await page.evaluate(cachedPaths())).filter((p) => p.startsWith('/api/'))).toEqual([])

  // The "New entry" shortcut opens the form
  await page.goto('/movimenti?new=1')
  await expect(page.locator('dialog[open]')).toContainText('Nuovo movimento')
  await expect(page).toHaveURL(/\/movimenti$/)

  // Offline the app still opens, and says it cannot reach the server
  await context.setOffline(true)
  await page.goto('/')
  await expect(page.getByText('Impossibile contattare il server')).toBeVisible({ timeout: 15_000 })
  await context.setOffline(false)
})

test('a new build is offered once downloaded, and one click switches to it', async ({ signedIn: page }) => {
  await page.goto('/')
  await page.evaluate(async () => { await navigator.serviceWorker.ready })
  await page.reload()
  const banner = page.getByRole('status').filter({ hasText: 'È disponibile una nuova versione.' })
  await expect(banner).toHaveCount(0)

  // A deploy: the served service worker changes
  const original = await readFile(SERVICE_WORKER, 'utf8')
  try {
    await writeFile(SERVICE_WORKER, original.replace(/const VERSION = "[0-9a-f]+"/, 'const VERSION = "next-build"'))
    await page.evaluate(async () => { await (await navigator.serviceWorker.getRegistration())!.update() })
    await expect(banner).toBeVisible()
    await banner.getByRole('button', { name: 'Aggiorna' }).click()
    await expect(banner).toHaveCount(0)
    await expect(page.getByRole('navigation', { name: 'Navigazione principale' })).toBeVisible()
    // Only the new build's files are kept
    await expect.poll(() => page.evaluate(() => caches.keys())).toEqual(['finanze-next-build'])
  } finally {
    await writeFile(SERVICE_WORKER, original)
  }
})

test.describe('in English', () => {
  test.use({ language: 'EN' })

  test('the installed app is named in the user\'s language', async ({ signedIn: page }) => {
    await page.goto('/')
    await expect(page.locator('link[rel="manifest"]')).toHaveAttribute('href', '/manifest-en.webmanifest')
    const manifest = await (await page.request.get('/manifest-en.webmanifest')).json() as { name: string; shortcuts: { name: string }[] }
    expect(manifest.name).toBe('Finances')
    expect(manifest.shortcuts.map((s) => s.name)).toEqual(['New transaction', 'Transactions', 'Budget'])
  })
})
