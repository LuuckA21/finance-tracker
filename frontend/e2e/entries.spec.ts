import { expect, test } from './fixtures'
import type { Page } from '@playwright/test'

async function addEntry(page: Page, entry: { kind: 'Uscita' | 'Entrata' | 'Trasferimento'; category?: string; amount: string; description?: string }) {
  await page.getByRole('button', { name: 'Nuovo movimento' }).click()
  const dialog = page.locator('dialog[open]')
  await dialog.getByRole('radio', { name: entry.kind }).click()
  if (entry.category) await dialog.getByLabel('Categoria').selectOption({ label: entry.category })
  await dialog.getByLabel('Importo').fill(entry.amount)
  if (entry.description) await dialog.getByLabel('Descrizione (facoltativa)').fill(entry.description)
  await dialog.getByRole('button', { name: 'Salva', exact: true }).click()
  await expect(dialog).toBeHidden()
}

test('income, expenses and transfers: add, filter, edit, delete', async ({ signedIn: page }) => {
  await page.goto('/movimenti')
  await expect(page.getByText('Nessun movimento')).toBeVisible()

  await addEntry(page, { kind: 'Uscita', category: 'Spesa alimentare', amount: '45.20', description: 'Migros' })
  await addEntry(page, { kind: 'Entrata', category: 'Stipendio', amount: '6000', description: 'Stipendio agosto' })
  await addEntry(page, { kind: 'Trasferimento', amount: '500', description: 'Verso il risparmio' })

  const rows = page.locator('tbody tr')
  await expect(rows).toHaveCount(3)
  await expect(rows.filter({ hasText: 'Migros' })).toContainText('Spesa alimentare')
  await expect(rows.filter({ hasText: 'Verso il risparmio' })).toContainText('Trasferimento')

  // Filters
  await page.getByLabel('Tipo').selectOption({ label: 'Solo uscite' })
  await expect(rows).toHaveCount(1)
  await expect(rows.first()).toContainText('Migros')
  await page.getByLabel('Tipo').selectOption({ label: 'Tutti i tipi' })
  await page.getByPlaceholder('Cerca descrizione…').fill('stipendio')
  await page.getByPlaceholder('Cerca descrizione…').press('Enter')
  await expect(rows).toHaveCount(1)
  await expect(rows.first()).toContainText('Stipendio agosto')
  await page.getByPlaceholder('Cerca descrizione…').fill('')
  await page.getByPlaceholder('Cerca descrizione…').press('Enter')
  await expect(rows).toHaveCount(3)

  // Edit
  const migros = rows.filter({ hasText: 'Migros' })
  await migros.getByRole('button', { name: 'Modifica' }).click()
  await page.locator('dialog[open]').getByLabel('Importo').fill('50')
  await page.locator('dialog[open]').getByRole('button', { name: 'Salva', exact: true }).click()
  await expect(migros).toContainText('50.00')

  // Income and expenses count in the cash flow, transfers stay out of it
  await page.getByRole('link', { name: 'Entrate e uscite' }).click()
  await expect(page.getByRole('heading', { name: 'Entrate e uscite' })).toBeVisible()
  const tiles = page.locator('main')
  await expect(tiles.getByText(/Entrate \d{4}/).locator('..')).toContainText(/6.000/)
  await expect(tiles.getByText(/Uscite \d{4}/).locator('..')).toContainText('50')
  await expect(tiles.getByText(/Trasferito \d{4}/).locator('..')).toContainText('500')

  // Delete
  await page.getByRole('link', { name: 'Movimenti', exact: true }).click()
  page.once('dialog', (d) => d.accept())
  await rows.filter({ hasText: 'Migros' }).getByRole('button', { name: 'Elimina' }).click()
  await expect(rows).toHaveCount(2)
  await expect(rows.filter({ hasText: 'Migros' })).toHaveCount(0)
})
