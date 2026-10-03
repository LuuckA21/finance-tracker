import { apiOk, expect, PASSWORD, test, totp, uniqueName } from './fixtures'

test('a new user signs in with the temporary password and must replace it', async ({ admin, page }) => {
  const username = uniqueName('e2e-new')
  const { user, temporaryPassword } = await apiOk<{ user: { id: number }; temporaryPassword: string }>(
    admin, 'POST', '/api/admin/users', { username, role: 'USER' })

  await page.goto('/')
  await expect(page).toHaveURL(/\/login$/)
  await page.getByLabel('Nome utente').fill(username)
  await page.getByLabel('Password').fill(temporaryPassword)
  await page.getByRole('button', { name: 'Accedi', exact: true }).click()

  await expect(page.getByRole('heading', { name: 'Imposta una nuova password' })).toBeVisible()
  await page.getByLabel('Password attuale').fill(temporaryPassword)
  await page.getByLabel('Nuova password', { exact: true }).fill(PASSWORD)
  await page.getByLabel('Conferma nuova password').fill(PASSWORD)
  await page.getByRole('button', { name: 'Cambia password' }).click()

  await expect(page.getByRole('heading', { name: `Ciao ${username}` })).toBeVisible()

  await page.getByRole('button', { name: 'Esci' }).click()
  await expect(page).toHaveURL(/\/login$/)
  await page.goto('/movimenti')
  await expect(page).toHaveURL(/\/login$/)

  await apiOk(admin, 'DELETE', `/api/admin/users/${user.id}`)
})

test('wrong credentials are refused', async ({ page, user }) => {
  await page.goto('/login')
  await page.getByLabel('Nome utente').fill(user.username)
  await page.getByLabel('Password').fill('not-the-password-at-all')
  await page.getByRole('button', { name: 'Accedi', exact: true }).click()
  await expect(page.getByRole('alert')).toHaveText('Nome utente o password non validi.')
  await expect(page).toHaveURL(/\/login$/)
})

test('two-step verification: enable it, then sign in with a code', async ({ signedIn: page, user }) => {
  await page.goto('/impostazioni')
  await page.getByRole('button', { name: 'Attiva la verifica in due passaggi' }).click()
  const dialog = page.locator('dialog[open]')
  await expect(dialog.getByRole('img', { name: 'Codice QR per l\'app di autenticazione' })).toBeVisible()
  await dialog.getByText('Non riesci a scansionare?').click()
  const secret = (await dialog.locator('code').textContent())!.trim()

  await dialog.locator('input[type=password]').fill(user.password)
  await dialog.locator('input[inputmode=numeric]').fill(totp(secret))
  await dialog.getByRole('button', { name: 'Attiva' }).click()
  await expect(dialog.getByRole('heading', { name: 'Codici di recupero' })).toBeVisible()
  await expect(dialog.locator('ul li')).toHaveCount(10)
  await dialog.getByRole('button', { name: 'Li ho salvati' }).click()
  await expect(page.getByText('Attiva', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: 'Esci' }).click()
  await page.getByLabel('Nome utente').fill(user.username)
  await page.getByLabel('Password').fill(user.password)
  await page.getByRole('button', { name: 'Accedi', exact: true }).click()
  await expect(page.getByText('Verifica in due passaggi')).toBeVisible()
  await page.getByLabel('Codice dell\'app di autenticazione').fill('000000')
  await page.getByRole('button', { name: 'Verifica' }).click()
  await expect(page.getByRole('alert')).toHaveText('Codice non valido.')

  // The code used to enable 2FA cannot be replayed: use the next time step
  await page.getByLabel('Codice dell\'app di autenticazione').fill(totp(secret, 1))
  await page.getByRole('button', { name: 'Verifica' }).click()
  await expect(page.getByRole('heading', { name: `Ciao ${user.username}` })).toBeVisible()
})
