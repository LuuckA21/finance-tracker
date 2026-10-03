import { expect, PASSWORD, test } from './fixtures'

test('passkeys: add one in the settings, then sign in with it without password or username', async ({ signedIn: page }) => {
  // A device with a passkey store and fingerprint/face check, which always succeeds
  const cdp = await page.context().newCDPSession(page)
  await cdp.send('WebAuthn.enable')
  await cdp.send('WebAuthn.addVirtualAuthenticator', {
    options: {
      protocol: 'ctap2', transport: 'internal', hasResidentKey: true, hasUserVerification: true,
      isUserVerified: true, automaticPresenceSimulation: true,
    },
  })

  await page.goto('/impostazioni')
  const card = page.getByRole('region', { name: 'Passkey' })
  await card.getByRole('button', { name: 'Aggiungi una passkey' }).click()
  const dialog = page.locator('dialog[open]')
  // A wrong password stops it before the device is asked
  await dialog.getByLabel('Nome').fill('Portatile')
  await dialog.getByLabel('Password').fill('not-the-password')
  await dialog.getByRole('button', { name: 'Crea la passkey' }).click()
  await expect(dialog.getByRole('alert')).toBeVisible()
  await dialog.getByLabel('Password').fill(PASSWORD)
  await dialog.getByRole('button', { name: 'Crea la passkey' }).click()
  await expect(dialog).toHaveCount(0)
  await expect(card.locator('li').filter({ hasText: 'Portatile' })).toContainText('mai usata')

  // Out, and back in with the passkey alone
  await page.getByRole('button', { name: 'Esci' }).first().click()
  await page.getByRole('button', { name: 'Accedi con una passkey' }).click()
  await expect(page.getByRole('navigation', { name: 'Navigazione principale' })).toBeVisible()

  await page.goto('/impostazioni')
  await expect(page.getByRole('region', { name: 'Accessi recenti' })).toContainText('Accesso con passkey')
  await expect(card.locator('li').filter({ hasText: 'Portatile' })).toContainText('ultimo uso')

  // Removed, it no longer signs in
  page.once('dialog', (d) => d.accept())
  await card.getByRole('button', { name: 'Elimina la passkey Portatile' }).click()
  await expect(card.locator('li').filter({ hasText: 'Portatile' })).toHaveCount(0)
  await page.getByRole('button', { name: 'Esci' }).first().click()
  await page.getByRole('button', { name: 'Accedi con una passkey' }).click()
  await expect(page.getByRole('alert')).toContainText('non è stata accettata')
})
