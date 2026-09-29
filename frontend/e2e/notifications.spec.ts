import { expect, test, uniqueName } from './fixtures'
import { mailsTo } from './mail'

test('email notifications: confirm an address with its code, send a test, choose alerts, remove it', async ({ signedIn: page }) => {
  const address = `${uniqueName('notify')}@example.test`
  await page.goto('/impostazioni')
  await page.getByRole('link', { name: 'Notifiche' }).click()
  await expect(page).toHaveURL(/\/impostazioni\/notifiche$/)
  const email = page.getByRole('region', { name: 'Email' })
  await expect(email.getByText('Avvisi e riepiloghi arrivano solo')).toBeVisible()

  // A code goes to the address; a wrong one is refused, the right one confirms it
  await email.getByLabel('Indirizzo email').fill(address)
  await email.getByRole('button', { name: 'Invia codice' }).click()
  await expect(email.getByText(`Abbiamo inviato un codice a ${address}`)).toBeVisible()
  const [codeMail] = await mailsTo(address)
  expect(codeMail.subject).toBe('Finanze: codice di conferma')
  const code = /^\s+(\d{6})\s*$/m.exec(codeMail.text)![1]
  await email.getByLabel('Codice').fill(code === '000000' ? '111111' : '000000')
  await email.getByRole('button', { name: 'Conferma' }).click()
  await expect(email.getByRole('alert')).toHaveText('Codice errato.')
  await email.getByLabel('Codice').fill(code)
  await email.getByRole('button', { name: 'Conferma' }).click()
  await expect(email.getByText('Le notifiche arrivano a')).toContainText(address)

  await email.getByRole('button', { name: 'Invia email di prova' }).click()
  await expect(email.getByRole('status')).toHaveText(`Email di prova inviata a ${address}.`)
  const mails = await mailsTo(address, 2)
  expect(mails[1].subject).toBe('Finanze: email di prova')
  expect(mails[1].text).toContain('questa è un\'email di prova')

  // Alerts: all on by default; a change is kept
  const alerts = page.getByRole('region', { name: 'Cosa ricevere' })
  await expect(alerts.getByLabel('Avvisi sui budget')).toBeChecked()
  await alerts.getByLabel('Riepilogo mensile').uncheck()
  await expect(alerts.getByLabel('Riepilogo mensile')).not.toBeChecked()
  await page.reload()
  await expect(page.getByRole('region', { name: 'Cosa ricevere' }).getByLabel('Riepilogo mensile')).not.toBeChecked()

  page.once('dialog', (d) => d.accept())
  await page.getByRole('region', { name: 'Email' }).getByRole('button', { name: 'Rimuovi' }).click()
  await expect(page.getByRole('region', { name: 'Email' }).getByLabel('Indirizzo email')).toBeVisible()
})
