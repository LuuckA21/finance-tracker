import { apiOk, expect, test } from './fixtures'

test('annual report: a closed year against the one before, net worth, positions and printing', async ({ signedIn: page }) => {
  const request = page.request
  const year = new Date().getFullYear() - 1
  const categories = await apiOk<{ id: number; name: string }[]>(request, 'GET', '/api/categories')
  const id = (name: string) => categories.find((c) => c.name === name)!.id
  const entry = (date: string, kind: string, category: string, amount: number, description: string, tags: string[] = []) =>
    apiOk(request, 'POST', '/api/cash-entries', { date, kind, categoryId: id(category), amount, currency: 'CHF', description, tags })

  const bank = await apiOk<{ id: number }>(request, 'POST', '/api/positions',
    { name: 'Conto corrente', symbol: '', assetClass: 'CASH', currency: 'CHF', notes: '', archived: false })
  const pillar = await apiOk<{ id: number }>(request, 'POST', '/api/positions',
    { name: 'Pilastro 3a', symbol: '', assetClass: 'PENSION', currency: 'CHF', notes: '', archived: false })
  await apiOk(request, 'POST', `/api/positions/${bank.id}/snapshots`, { date: `${year - 1}-12-31`, quantity: 20000, unitPrice: 1, note: '' })
  await apiOk(request, 'POST', `/api/positions/${bank.id}/snapshots`, { date: `${year}-12-31`, quantity: 22000, unitPrice: 1, note: '' })
  await apiOk(request, 'POST', `/api/positions/${pillar.id}/snapshots`, { date: `${year}-12-31`, quantity: 7300, unitPrice: 1, note: '' })
  await apiOk(request, 'POST', '/api/cash-entries',
    { date: `${year}-06-30`, kind: 'TRANSFER', fromPositionId: bank.id, toPositionId: pillar.id, amount: 7000, currency: 'CHF', description: '3a' })
  await entry(`${year}-01-25`, 'INCOME', 'Stipendio', 72000, 'Stipendio')
  await entry(`${year}-03-01`, 'EXPENSE', 'Casa', 21600, 'Affitto')
  await entry(`${year}-07-10`, 'EXPENSE', 'Viaggi', 800, 'Traghetto', ['Vacanze'])
  await entry(`${year - 1}-03-01`, 'EXPENSE', 'Casa', 20400, 'Affitto')

  await page.goto('/')
  await page.getByRole('link', { name: 'Riepilogo annuale' }).click()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(`Riepilogo ${new Date().getFullYear()}`)
  await page.getByLabel('Anno', { exact: true }).selectOption(String(year))
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(`Riepilogo ${year}`)
  await expect(page.getByText(`01.01.${year} – 31.12.${year} · importi in CHF`)).toBeVisible()

  // Totals with their monthly average and last year's figure
  await expect(page.getByText(/Media mensile: CHF\s?6.?000/)).toBeVisible()
  await expect(page.getByText(new RegExp(`Nel ${year - 1}: CHF\\s?20.?400`))).toBeVisible()

  const categories2 = page.getByRole('region', { name: 'Entrate e uscite per categoria' })
  const home = categories2.getByRole('row').filter({ hasText: 'Casa' })
  await expect(home).toContainText(/\+CHF\s?1.?200/)

  // Net worth from 31 December of the year before; the pillar got 7000 of transfers
  const positions = page.getByRole('region', { name: 'Posizioni' })
  await expect(positions.getByRole('row').filter({ hasText: 'Pilastro 3a' })).toContainText(/\+CHF\s?7.?000/)
  await expect(positions.getByRole('row').filter({ hasText: 'Conto corrente' })).toContainText(/−CHF\s?7.?000/)
  await expect(page.getByRole('region', { name: 'Patrimonio per classe' }).getByRole('row').filter({ hasText: 'Totale' }))
    .toContainText(/\+CHF\s?9.?300/)
  await expect(page.getByRole('region', { name: "Etichette dell'anno" })).toContainText('Vacanze')
  await expect(page.getByRole('region', { name: 'Le spese più grandi' }).getByRole('row').nth(1)).toContainText('Affitto')

  // Printing hides the navigation and keeps the report
  await page.emulateMedia({ media: 'print' })
  await expect(page.getByRole('navigation', { name: 'Navigazione principale' })).toBeHidden()
  await expect(page.getByRole('button', { name: 'Stampa / PDF' })).toBeHidden()
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
})
