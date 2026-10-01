import { apiOk, expect, test, today } from './fixtures'

test('a budget shows what is left and warns on the overview when exceeded', async ({ signedIn: page }) => {
  const categories = await apiOk<{ id: number; name: string }[]>(page.request, 'GET', '/api/categories')
  const restaurants = categories.find((c) => c.name === 'Ristoranti')!
  await apiOk(page.request, 'POST', '/api/cash-entries', {
    date: today(), kind: 'EXPENSE', categoryId: restaurants.id, amount: 120, currency: 'CHF', description: 'Cena',
  })

  await page.goto('/budget')
  await expect(page.getByText('Nessun budget')).toBeVisible()
  await page.getByRole('button', { name: 'Nuovo budget' }).click()
  const dialog = page.locator('dialog[open]')
  await dialog.getByLabel('Categoria').selectOption({ label: 'Ristoranti' })
  await dialog.getByLabel('Importo mensile').fill('200')
  await dialog.getByRole('button', { name: 'Salva' }).click()

  const row = page.locator('li').filter({ hasText: 'Ristoranti' }).first()
  await expect(row).toContainText(/Restano CHF\s?80/)
  await expect(row).toContainText('60 % usato')

  // Lower the limit below what was spent
  await row.getByRole('button', { name: 'Modifica il budget di Ristoranti' }).click()
  await dialog.getByLabel('Importo mensile').fill('100')
  await dialog.getByRole('button', { name: 'Salva' }).click()
  await expect(row).toContainText('Superato')
  await expect(row).toContainText(/Superato di CHF\s?20/)

  await page.getByRole('link', { name: 'Panoramica' }).click()
  await expect(page.getByRole('status').filter({ hasText: 'Budget superato: 1' })).toContainText('Ristoranti')
})

test('a yearly budget compares the year so far instead of the month', async ({ signedIn: page }) => {
  const categories = await apiOk<{ id: number; name: string }[]>(page.request, 'GET', '/api/categories')
  const health = categories.find((c) => c.name === 'Cassa malati')!
  const year = new Date().getFullYear()
  // The yearly premium in January, a top-up today
  for (const [date, amount] of [[`${year}-01-15`, 3600], [today(), 100]] as const) {
    await apiOk(page.request, 'POST', '/api/cash-entries', {
      date, kind: 'EXPENSE', categoryId: health.id, amount, currency: 'CHF', description: 'Premio',
    })
  }

  await page.goto('/budget')
  await page.getByRole('button', { name: 'Nuovo budget' }).click()
  const dialog = page.locator('dialog[open]')
  await dialog.getByLabel('Categoria').selectOption({ label: 'Assicurazioni › Cassa malati' })
  await dialog.getByRole('radio', { name: 'Annuale' }).click()
  await expect(dialog.getByText('Trimestri e anni sono quelli del calendario', { exact: false })).toBeVisible()
  await dialog.getByLabel('Importo annuale').fill('4000')
  await dialog.getByRole('button', { name: 'Salva' }).click()

  const row = page.getByRole('region', { name: 'Budget trimestrali e annuali' }).locator('li').filter({ hasText: 'Cassa malati' })
  await expect(row).toContainText(`Anno ${year}`)
  await expect(row).toContainText(/Restano CHF\s?300/)
  await expect(row).toContainText('Quasi esaurito')
  await expect(row).toContainText('Questo mese')
  await expect(row).toContainText(/Anno precedente: CHF\s?0/)
  // The month's totals leave it out
  await expect(page.getByText('solo budget mensili')).toBeVisible()

  // Back to monthly: now this month alone
  await row.getByRole('button', { name: 'Modifica il budget di Assicurazioni › Cassa malati' }).click()
  await expect(dialog.getByRole('radio', { name: 'Annuale' })).toHaveAttribute('aria-checked', 'true')
  await dialog.getByRole('radio', { name: 'Mensile' }).click()
  await dialog.getByLabel('Importo mensile').fill('400')
  await dialog.getByRole('button', { name: 'Salva' }).click()
  await expect(page.getByRole('region', { name: 'Budget trimestrali e annuali' })).toHaveCount(0)
  // Monthly again: the row compares the month (with the recent average beside it)
  await expect(page.locator('li').filter({ hasText: 'Cassa malati' })).toContainText('Media ultimi 3 mesi')
})
