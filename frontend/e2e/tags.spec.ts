import { expect, test } from './fixtures'
import type { Page } from '@playwright/test'

async function addExpense(page: Page, category: string, amount: string, description: string, tags: string[]) {
  await page.getByRole('button', { name: 'Nuovo movimento' }).click()
  const dialog = page.locator('dialog[open]')
  await dialog.getByLabel('Categoria').selectOption({ label: category })
  await dialog.getByLabel('Importo').fill(amount)
  await dialog.getByLabel('Descrizione (facoltativa)').fill(description)
  const input = dialog.getByLabel('Etichette')
  for (const tag of tags) {
    await input.fill(tag)
    await input.press('Enter')
  }
  await dialog.getByRole('button', { name: 'Salva', exact: true }).click()
  await expect(dialog).toBeHidden()
}

test('tags: add them to entries, filter by one, see its totals, rename and delete it', async ({ signedIn: page }) => {
  await page.goto('/movimenti')
  await addExpense(page, 'Viaggi', '800', 'Traghetto', ['Vacanze Sardegna', 'Famiglia'])
  // Typed in another case: the existing tag is reused
  await addExpense(page, 'Ristoranti', '120', 'Cena al porto', ['vacanze sardegna'])
  await addExpense(page, 'Spesa alimentare', '60', 'Migros', [])

  const rows = page.locator('tbody tr')
  await expect(rows).toHaveCount(3)
  const dinner = rows.filter({ hasText: 'Cena al porto' })
  await expect(dinner.getByRole('button', { name: 'Vacanze Sardegna' })).toBeVisible()

  // Clicking a tag filters the list and shows what it adds up to
  await dinner.getByRole('button', { name: 'Vacanze Sardegna' }).click()
  await expect(rows).toHaveCount(2)
  const summary = page.getByRole('status').filter({ hasText: 'Vacanze Sardegna' })
  await expect(summary).toContainText('Movimenti: 2')
  await expect(summary).toContainText(/Uscite: CHF\s?920/)
  await expect(page.getByLabel('Etichette', { exact: true })).toHaveValue(/\d+/)
  await page.getByLabel('Etichette', { exact: true }).selectOption({ label: 'Tutte le etichette' })
  await expect(rows).toHaveCount(3)

  // Editing an entry: remove a tag with its chip button
  await rows.filter({ hasText: 'Traghetto' }).getByRole('button', { name: 'Modifica' }).click()
  const dialog = page.locator('dialog[open]')
  await dialog.getByRole('button', { name: 'Rimuovi Famiglia' }).click()
  await dialog.getByRole('button', { name: 'Salva', exact: true }).click()
  await expect(rows.filter({ hasText: 'Traghetto' }).getByRole('button', { name: 'Famiglia' })).toHaveCount(0)

  // Settings: rename and delete
  await page.goto('/impostazioni/etichette')
  const holiday = page.locator('li').filter({ hasText: 'Vacanze Sardegna' })
  await expect(holiday).toContainText('Movimenti: 2')
  await holiday.getByRole('button', { name: 'Rinomina Vacanze Sardegna' }).click()
  await page.locator('dialog[open]').getByLabel('Nome').fill('Sardegna 2026')
  await page.locator('dialog[open]').getByRole('button', { name: 'Salva' }).click()
  await expect(page.locator('li').filter({ hasText: 'Sardegna 2026' })).toBeVisible()

  page.once('dialog', (d) => d.accept())
  await page.locator('li').filter({ hasText: 'Famiglia' }).getByRole('button', { name: 'Elimina Famiglia' }).click()
  await expect(page.locator('li').filter({ hasText: 'Famiglia' })).toHaveCount(0)

  // The entries show the new name
  await page.goto('/movimenti')
  await expect(page.locator('tbody tr').filter({ hasText: 'Cena al porto' }).getByRole('button', { name: 'Sardegna 2026' })).toBeVisible()
})
