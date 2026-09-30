import { expect, test } from './fixtures'
import type { Page } from '@playwright/test'

async function addExpense(page: Page, category: string, amount: string, description: string) {
  await page.getByRole('button', { name: 'Nuovo movimento' }).click()
  const dialog = page.locator('dialog[open]')
  await dialog.getByRole('radio', { name: 'Uscita' }).click()
  await dialog.getByLabel('Categoria').selectOption({ label: category })
  await dialog.getByLabel('Importo').fill(amount)
  await dialog.getByLabel('Descrizione (facoltativa)').fill(description)
  await dialog.getByRole('button', { name: 'Salva', exact: true }).click()
  await expect(dialog).toBeHidden()
}

test('macro and detail categories: add a detail, book on it, filter and drill down by macro', async ({ signedIn: page }) => {
  // A new detail under a default macro, taking its colour
  await page.goto('/impostazioni/categorie')
  const expenses = page.getByRole('region', { name: 'Categorie di uscita' })
  await expect(expenses.getByText('Affitto', { exact: true })).toBeVisible()
  await expenses.getByRole('button', { name: 'Aggiungi un dettaglio a Casa' }).click()
  const dialog = page.locator('dialog[open]')
  await expect(dialog.getByLabel('Macro categoria')).toHaveValue(/\d+/)
  await dialog.getByLabel('Nome').fill('Garage')
  await dialog.getByRole('button', { name: 'Salva' }).click()
  await expect(dialog).toBeHidden()
  await expect(expenses.getByText('Garage', { exact: true })).toBeVisible()

  // A macro with details cannot be deleted
  page.once('dialog', (confirm) => confirm.accept())
  await expenses.getByRole('button', { name: 'Elimina Casa', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('La categoria ha dei dettagli')

  // Entries on the details and on the macro itself
  await page.goto('/movimenti')
  await addExpense(page, 'Casa › Affitto', '1800', 'Affitto ottobre')
  await addExpense(page, 'Casa › Garage', '150', 'Box auto')
  await addExpense(page, 'Casa', '60', 'Lampadine')
  await addExpense(page, 'Svago', '40', 'Cinema')
  const rows = page.locator('tbody tr')
  await expect(rows.filter({ hasText: 'Affitto ottobre' })).toContainText('Casa › Affitto')

  // Filtering on the macro takes in its details
  await page.getByLabel('Categoria').selectOption({ label: 'Casa' })
  await expect(rows).toHaveCount(3)
  await page.getByLabel('Categoria').selectOption({ label: 'Casa › Garage' })
  await expect(rows).toHaveCount(1)
  await expect(rows.first()).toContainText('Box auto')

  // The cash flow by macro opens on its details
  await page.goto('/flussi')
  const byCategory = page.getByRole('region', { name: 'Uscite per categoria' })
  const home = byCategory.locator('details').filter({ hasText: 'Casa' })
  await expect(home.locator('summary')).toContainText(/CHF\s?2.?010/)
  await expect(home.getByText('Garage')).toBeHidden()
  await home.locator('summary').click()
  await expect(home.getByText('Affitto')).toBeVisible()
  await expect(home.getByText('Garage')).toBeVisible()
  await expect(home.getByText('Casa (senza dettaglio)')).toBeVisible()
})
