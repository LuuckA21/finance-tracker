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
