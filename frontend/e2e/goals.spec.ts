import { apiOk, expect, test, today } from './fixtures'

/** yyyy-MM-dd of a day `months` from today (same day of month, clamped). */
function monthsFromToday(months: number) {
  const [y, m, d] = today().split('-').map(Number)
  const target = new Date(Date.UTC(y, m - 1 + months, 1))
  const last = new Date(Date.UTC(target.getUTCFullYear(), target.getUTCMonth() + 1, 0)).getUTCDate()
  target.setUTCDate(Math.min(d, last))
  return target.toISOString().slice(0, 10)
}

test('savings goals: a target balance and a yearly contribution', async ({ signedIn: page }) => {
  const request = page.request
  const savings = await apiOk<{ id: number }>(request, 'POST', '/api/positions',
    { name: 'Conto risparmio', symbol: '', assetClass: 'CASH', currency: 'CHF', notes: '', archived: false })
  await apiOk(request, 'POST', `/api/positions/${savings.id}/snapshots`, { date: monthsFromToday(-6), quantity: 5000, unitPrice: 1, note: '' })
  await apiOk(request, 'POST', `/api/positions/${savings.id}/snapshots`, { date: today(), quantity: 8000, unitPrice: 1, note: '' })
  const pillar = await apiOk<{ id: number }>(request, 'POST', '/api/positions',
    { name: 'Pilastro 3a', symbol: '', assetClass: 'PENSION', currency: 'CHF', notes: '', archived: false })
  await apiOk(request, 'POST', '/api/cash-entries',
    { date: today(), kind: 'TRANSFER', categoryId: null, toPositionId: pillar.id, amount: 2000, currency: 'CHF', description: '3a' })

  await page.goto('/obiettivi')
  await expect(page.getByText('Nessun obiettivo')).toBeVisible()

  // A balance to reach: 8000 of 20000, 500 a month over the last 6 months
  await page.getByRole('button', { name: 'Nuovo obiettivo' }).click()
  const dialog = page.locator('dialog[open]')
  await dialog.getByLabel('Nome').fill('Fondo emergenza')
  await dialog.getByLabel('Importo da raggiungere').fill("20'000")
  await dialog.getByRole('button', { name: 'Salva' }).click()
  await expect(dialog.getByText('Scegli almeno una posizione.')).toBeVisible()
  await dialog.getByRole('checkbox', { name: 'Conto risparmio' }).check()
  await dialog.getByRole('button', { name: 'Salva' }).click()
  await expect(dialog).toBeHidden()

  const emergency = page.locator('li').filter({ hasText: 'Fondo emergenza' })
  await expect(emergency.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '40')
  await expect(emergency).toContainText('Ultimi 6 mesi: CHF 500 al mese')
  await expect(emergency).toContainText('A questo ritmo:')
  await expect(emergency).toContainText('Conto risparmio')

  // A yearly contribution counts this year's transfers into the pillar 3a
  await page.getByRole('button', { name: 'Nuovo obiettivo' }).click()
  await dialog.getByLabel('Nome').fill('Pilastro 3a')
  await dialog.getByRole('radio', { name: 'Versamento annuale' }).click()
  await expect(dialog.getByLabel('Entro il (facoltativo)')).toHaveCount(0)
  await dialog.getByLabel("Importo all'anno").fill('7258')
  await dialog.getByRole('checkbox', { name: 'Pilastro 3a' }).check()
  await dialog.getByRole('button', { name: 'Salva' }).click()
  const yearly = page.locator('li').filter({ hasText: 'Versamento annuale' })
  await expect(yearly).toContainText(/CHF\s?2.?000/)
  await expect(yearly).toContainText('Servono')

  // Edit: with a lower target the goal is reached
  await emergency.getByRole('button', { name: 'Modifica Fondo emergenza' }).click()
  await dialog.getByLabel('Importo da raggiungere').fill('6000')
  await dialog.getByRole('button', { name: 'Salva' }).click()
  await expect(emergency).toContainText('Raggiunto')

  // The overview shows them too
  await page.getByRole('link', { name: 'Panoramica' }).click()
  const card = page.locator('section').filter({ hasText: 'Obiettivi' })
  await expect(card.getByRole('progressbar')).toHaveCount(2)

  // Delete
  await page.goto('/obiettivi')
  page.once('dialog', (d) => d.accept())
  await page.locator('li').filter({ hasText: 'Pilastro 3a' }).getByRole('button', { name: 'Elimina Pilastro 3a' }).click()
  await expect(page.getByRole('progressbar')).toHaveCount(1)
})
