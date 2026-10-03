import { readFile } from 'node:fs/promises'
import { apiLogin, apiOk, expect, PASSWORD, test, uniqueName } from './fixtures'

test('all of my data in one ZIP from the settings', async ({ signedIn: page, user }) => {
  await page.goto('/impostazioni')
  const card = page.getByRole('region', { name: 'I tuoi dati' })
  const download = page.waitForEvent('download')
  await card.getByRole('button', { name: 'Scarica i miei dati' }).click()
  const file = await download
  expect(file.suggestedFilename()).toMatch(new RegExp(`^finanze-${user.username}-\\d{4}-\\d{2}-\\d{2}\\.zip$`))
  const zip = await readFile((await file.path())!)
  // A ZIP (its file names are stored in clear): the data and the entries as CSV
  expect(zip.subarray(0, 2).toString()).toBe('PK')
  expect(zip.includes('data.json')).toBe(true)
  expect(zip.includes('movimenti-')).toBe(true)
})

test('deleting my own account signs me out and the account is gone', async ({ admin, page }) => {
  // Not the `user` fixture: that one deletes its user at the end, and here the user deletes itself
  const username = uniqueName('e2e-bye')
  const created = await apiOk<{ temporaryPassword: string }>(admin, 'POST', '/api/admin/users',
    { username, role: 'USER', language: 'IT' })
  await apiLogin(page.request, { username, password: created.temporaryPassword })
  await apiOk(page.request, 'PUT', '/api/account/password', { currentPassword: created.temporaryPassword, newPassword: PASSWORD })

  await page.goto('/impostazioni')
  await page.getByRole('region', { name: 'I tuoi dati' }).getByRole('button', { name: 'Elimina l\'account' }).click()
  const dialog = page.locator('dialog[open]')
  const confirm = dialog.getByRole('button', { name: 'Elimina definitivamente' })
  await dialog.getByLabel('Password').fill('not-the-password')
  // Only once the username is typed again
  await expect(confirm).toBeDisabled()
  await dialog.getByLabel(/nome utente/).fill(username)
  await confirm.click()
  await expect(dialog.getByRole('alert')).toContainText('password')

  await dialog.getByLabel('Password').fill(PASSWORD)
  await confirm.click()
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByRole('status')).toContainText('eliminati')

  await page.getByLabel('Nome utente').fill(username)
  await page.getByLabel('Password').fill(PASSWORD)
  await page.getByRole('button', { name: 'Accedi', exact: true }).click()
  await expect(page.getByRole('alert')).toBeVisible()
  const users = await apiOk<{ username: string }[]>(admin, 'GET', '/api/admin/users')
  expect(users.map((u) => u.username)).not.toContain(username)
})
