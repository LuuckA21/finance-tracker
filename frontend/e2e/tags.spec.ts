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
  // ...split by category, largest first
  const byCategory = summary.getByRole('list', { name: 'Per categoria' }).getByRole('listitem')
  await expect(byCategory).toHaveCount(2)
  await expect(byCategory.first()).toContainText(/Viaggi\s*− CHF\s?800/)
  await expect(byCategory.last()).toContainText(/Ristoranti\s*− CHF\s?120/)
  await expect(page.getByLabel('Etichette', { exact: true })).toHaveValue(/\d+/)
  await page.getByLabel('Etichette', { exact: true }).selectOption({ label: 'Tutte le etichette' })
  await expect(rows).toHaveCount(3)

  // Income and expenses of the year, by tag: the ferry counts for both of its tags
  await page.goto('/flussi')
  const byTag = page.getByRole('region', { name: 'Uscite per etichetta' }).getByRole('listitem')
  await expect(byTag).toHaveCount(2)
  await expect(byTag.first()).toContainText(/Vacanze Sardegna\s*CHF\s?920/)
  await expect(byTag.last()).toContainText(/Famiglia\s*CHF\s?800/)
  await expect(page.getByRole('region', { name: 'Entrate per etichetta' })).toContainText('Nessuna entrata con etichette')

  // Categories × tags: every expense category, the ferry under both of its tags
  const matrix = page.getByRole('region', { name: 'Categorie × etichette' })
  await expect(matrix.getByRole('columnheader')).toHaveText(['Categoria', 'Vacanze Sardegna', 'Famiglia', 'Senza etichetta', 'Totale'])
  await expect(matrix.getByRole('row').filter({ hasText: 'Viaggi' }).getByRole('cell')).toHaveText([/800/, /800/, '—', /800/])
  await expect(matrix.getByRole('row').filter({ hasText: 'Ristoranti' }).getByRole('cell')).toHaveText([/120/, '—', '—', /120/])
  await expect(matrix.getByRole('row').filter({ hasText: 'Spesa alimentare' }).getByRole('cell')).toHaveText(['—', '—', /60/, /60/])
  await expect(matrix.locator('tfoot').getByRole('cell')).toHaveText([/920/, /800/, /60/, /980/])
  await matrix.getByRole('radio', { name: 'Entrate' }).click()
  await expect(matrix).toContainText('Nessuna entrata con etichette')
  await page.goto('/movimenti')

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

test('tags: a recurring rule passes its tags to the entries it creates', async ({ signedIn: page }) => {
  await page.goto('/ricorrenti')
  await page.getByRole('button', { name: 'Nuova ricorrenza' }).click()
  const dialog = page.locator('dialog[open]')
  await dialog.getByLabel('Descrizione (facoltativa)').fill('Abbonamento palestra')
  await dialog.getByLabel('Categoria').selectOption({ label: 'Svago' })
  await dialog.getByLabel('Importo').fill('70')
  const input = dialog.getByLabel('Etichette')
  await input.fill('Salute')
  await input.press('Enter')
  await dialog.getByRole('button', { name: 'Salva', exact: true }).click()
  await expect(dialog).toBeHidden()
  // Starting today: the first entry is created straight away
  await expect(page.locator('tbody tr').filter({ hasText: 'Abbonamento palestra' })).toContainText('Salute')

  await page.goto('/movimenti')
  const entry = page.locator('tbody tr').filter({ hasText: 'Abbonamento palestra' })
  await expect(entry.getByRole('button', { name: 'Salute' })).toBeVisible()
})
