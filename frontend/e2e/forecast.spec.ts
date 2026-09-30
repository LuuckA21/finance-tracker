import { apiOk, expect, test, today } from './fixtures'

/** The 10th of last month: inside the base, the last twelve complete months. */
function lastMonth() {
  const [y, m] = today().split('-').map(Number)
  return new Date(Date.UTC(y, m - 2, 10)).toISOString().slice(0, 10)
}

test('forecast: grow the last twelve months, leave out a one-off trip, add a rent, save and delete', async ({ signedIn: page }) => {
  const request = page.request
  const categories = await apiOk<{ id: number; name: string }[]>(request, 'GET', '/api/categories')
  const id = (name: string) => categories.find((c) => c.name === name)!.id
  const entry = (category: string, kind: string, amount: number, tags: string[] = []) =>
    apiOk(request, 'POST', '/api/cash-entries', { date: lastMonth(), kind, categoryId: id(category), amount, currency: 'CHF', description: category, tags })
  await entry('Stipendio', 'INCOME', 5000)
  await entry('Spesa alimentare', 'EXPENSE', 600)
  await entry('Viaggi', 'EXPENSE', 2000, ['Viaggio unico'])
  const year = Number(today().slice(0, 4)) + 1

  await page.goto('/previsione')
  await expect(page.getByText('Uno scenario parte dagli ultimi 12 mesi')).toBeVisible()
  const tile = (label: string) => page.getByText(label, { exact: true }).locator('..')
  // Straight from the base: everything of last month, the trip too
  await expect(tile(`Uscite ${year}`)).toContainText(/2.?600/)

  await page.getByLabel('Nome', { exact: true }).fill(`${year} con affitto`)
  await page.getByLabel('Crescita entrate (%)').fill('10')
  await page.getByText(/^Escludi dalla base/).click()
  await page.getByRole('group', { name: 'Etichette' }).getByRole('button', { name: 'Viaggio unico' }).click()
  await expect(page.getByRole('group', { name: 'Etichette' }).getByRole('button', { name: 'Viaggio unico' })).toHaveAttribute('aria-pressed', 'true')

  await page.getByRole('button', { name: 'Aggiungi voce' }).click()
  const dialog = page.locator('dialog[open]')
  await dialog.getByLabel('Descrizione').fill('Affitto')
  await dialog.getByLabel('Categoria').selectOption({ label: 'Casa' })
  await dialog.getByLabel('Importo (CHF)').fill('1500')
  await dialog.getByRole('button', { name: 'Salva', exact: true }).click()
  await expect(dialog).toBeHidden()

  // 5000 + 10%; 600 without the trip, plus twelve rents
  await expect(tile(`Entrate ${year}`)).toContainText(/5.?500/)
  await expect(tile(`Uscite ${year}`)).toContainText(/18.?600/)
  const items = page.locator('section').filter({ has: page.getByRole('heading', { name: 'Voci extra' }) })
  await expect(items.getByRole('row').filter({ hasText: 'Affitto' })).toContainText(/Ogni mese, gen – dic.*18.?000/)
  const comparison = page.getByRole('region', { name: 'Rispetto alla base' })
  await expect(comparison.getByRole('row').filter({ hasText: 'Uscite' }).getByRole('cell')).toHaveText([/600/, /0/, /\+.*18.?000/, /18.?600/])

  await page.getByRole('button', { name: 'Salva', exact: true }).click()
  await expect(page.getByRole('status')).toHaveText('Scenario salvato.')
  await expect(page.getByRole('combobox', { name: 'Scenario' })).toHaveValue(/\d+/)
  await expect(page.getByText('Modifiche non salvate')).toHaveCount(0)

  // Saved: back after a reload
  await page.reload()
  await expect(page.getByLabel('Nome', { exact: true })).toHaveValue(`${year} con affitto`)
  await expect(tile(`Uscite ${year}`)).toContainText(/18.?600/)

  page.once('dialog', (d) => d.accept())
  await page.getByRole('button', { name: 'Elimina' }).click()
  await expect(page.getByRole('combobox', { name: 'Scenario' })).toHaveCount(0)
  await expect(page.getByLabel('Nome', { exact: true })).toHaveValue('')
})
