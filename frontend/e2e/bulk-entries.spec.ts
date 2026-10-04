import { apiOk, expect, test } from './fixtures'

test('entries changed and deleted together: category for their kind, tags for all', async ({ signedIn: page }) => {
  const categories = await apiOk<{ id: number; name: string }[]>(page.request, 'GET', '/api/categories')
  const id = (name: string) => categories.find((c) => c.name === name)!.id
  const entry = (description: string, kind: string, category: string, tags: string[]) => apiOk(page.request, 'POST', '/api/cash-entries',
    { date: '2026-09-10', kind, categoryId: id(category), amount: 12, currency: 'CHF', description, tags })
  await entry('Pizzeria Napoli', 'EXPENSE', 'Supermercato', ['Vecchia'])
  await entry('Trattoria', 'EXPENSE', 'Supermercato', ['Vecchia'])
  await entry('Coop', 'EXPENSE', 'Supermercato', [])
  await entry('Lohn', 'INCOME', 'Stipendio', [])

  await page.goto('/movimenti')
  const rows = page.locator('tbody tr')
  await expect(rows).toHaveCount(4)
  await rows.filter({ hasText: 'Pizzeria Napoli' }).getByRole('checkbox').check()
  await rows.filter({ hasText: 'Trattoria' }).getByRole('checkbox').check()
  await rows.filter({ hasText: 'Lohn' }).getByRole('checkbox').check()
  const bar = page.getByRole('region', { name: 'Movimenti selezionati' })
  await expect(bar).toContainText('Selezionati: 3')

  // Restaurants for the two expenses (the income keeps its category), a tag for all, the old one out
  await bar.getByRole('button', { name: 'Modifica' }).click()
  const dialog = page.locator('dialog[open]')
  await dialog.getByLabel('Categoria').selectOption({ label: 'Ristoranti' })
  await dialog.getByLabel('Etichette da aggiungere').fill('Uscite di gruppo')
  await dialog.getByLabel('Etichette da aggiungere').press('Enter')
  await dialog.getByLabel('Etichette da togliere').fill('Vecchia')
  await dialog.getByLabel('Etichette da togliere').press('Enter')
  await dialog.getByRole('button', { name: 'Applica (3)' }).click()
  await expect(page.getByRole('status')).toContainText('Movimenti modificati: 3.')
  await expect(page.getByRole('status')).toContainText('Senza la nuova categoria perché di un altro tipo: 1.')
  await expect(bar).toHaveCount(0)

  const pizzeria = rows.filter({ hasText: 'Pizzeria Napoli' })
  await expect(pizzeria).toContainText('Ristoranti')
  await expect(pizzeria).toContainText('Uscite di gruppo')
  await expect(pizzeria).not.toContainText('Vecchia')
  await expect(rows.filter({ hasText: 'Lohn' })).toContainText('Stipendio')
  await expect(rows.filter({ hasText: 'Coop' })).toContainText('Supermercato')

  // One row, then every filtered one, then all gone
  await rows.filter({ hasText: 'Coop' }).getByRole('checkbox').check()
  await bar.getByRole('button', { name: 'Seleziona tutti i movimenti filtrati (4)' }).click()
  await expect(bar).toContainText('Selezionati: 4')
  page.once('dialog', (d) => d.accept())
  await bar.getByRole('button', { name: 'Elimina' }).click()
  await expect(page.getByRole('status')).toContainText('Movimenti eliminati: 4.')
  await expect(rows).toHaveCount(0)
})
