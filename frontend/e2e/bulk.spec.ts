import { apiOk, expect, test } from './fixtures'

test('update values: every active position at one date, saved together', async ({ signedIn: page }) => {
  const account = await apiOk<{ id: number }>(page.request, 'POST', '/api/positions',
    { name: 'Conto UBS', symbol: '', assetClass: 'CASH', currency: 'CHF', notes: '', archived: false })
  await apiOk(page.request, 'POST', `/api/positions/${account.id}/snapshots`, { date: '2026-01-31', quantity: 1000, unitPrice: 1, note: '' })
  await apiOk(page.request, 'POST', '/api/positions',
    { name: 'VWCE', symbol: 'VWCE', assetClass: 'ETF', currency: 'CHF', notes: '', archived: false })
  await apiOk(page.request, 'POST', '/api/positions',
    { name: 'Vecchio conto', symbol: '', assetClass: 'CASH', currency: 'CHF', notes: '', archived: true })

  await page.goto('/aggiorna')
  // Archived positions are not offered; the rows start from the latest record
  await expect(page.getByText('Vecchio conto')).toHaveCount(0)
  await expect(page.getByLabel('Quantità Conto UBS')).toHaveValue('1000')
  await expect(page.getByLabel('Quantità VWCE')).toHaveValue('')

  await page.getByLabel('Data della rilevazione').fill('2026-02-28')
  await page.getByLabel('Quantità Conto UBS').fill("1'250.50")
  await page.getByLabel('Quantità VWCE').fill('10')
  await page.getByLabel('Prezzo VWCE').fill('125,40')
  await page.getByRole('button', { name: 'Salva rilevazioni del 28.02.2026' }).click()

  await expect(page).toHaveURL(/\/patrimonio$/)
  const snapshots = await apiOk<{ date: string; quantity: number; unitPrice: number }[]>(
    page.request, 'GET', `/api/positions/${account.id}/snapshots`)
  expect(snapshots.map((s) => [s.date, s.quantity])).toEqual([['2026-02-28', 1250.5], ['2026-01-31', 1000]])
  // 1'250.50 + 10 × 125.40, rounded to the franc
  await expect(page.getByText(/^Totale al /).locator('..')).toContainText(/2.?505/)
})

test('update values: an unreadable number blocks the save', async ({ signedIn: page }) => {
  await apiOk(page.request, 'POST', '/api/positions',
    { name: 'Conto UBS', symbol: '', assetClass: 'CASH', currency: 'CHF', notes: '', archived: false })
  await page.goto('/aggiorna')
  await page.getByLabel('Quantità Conto UBS').fill('mille')
  await page.getByRole('button', { name: /Salva rilevazioni/ }).click()
  await expect(page.getByRole('alert')).toHaveText('Valori non validi per “Conto UBS”.')
  await expect(page).toHaveURL(/\/aggiorna$/)
})
