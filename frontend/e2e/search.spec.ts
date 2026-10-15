import type { Page } from '@playwright/test'
import { apiOk, expect, test, today } from './fixtures'

/** "/" opens the search box once the page is idle: no dialog open (the shortcut waits for them to close). */
async function openSearch(page: Page) {
  await expect(page.locator('dialog[open]')).toHaveCount(0)
  await page.keyboard.press('/')
  await expect(page.getByRole('combobox', { name: 'Cerca' })).toBeFocused()
}

test('search box: "/" opens it, results open the entry, the position, the recurring entry and the goal', async ({ signedIn: page }) => {
  const request = page.request
  const categories = await apiOk<{ id: number; name: string }[]>(request, 'GET', '/api/categories')
  const shop = categories.find((c) => c.name === 'Supermercato')!.id
  await apiOk(request, 'POST', '/api/cash-entries',
    { date: today(), kind: 'EXPENSE', categoryId: shop, amount: 45.2, currency: 'CHF', description: 'Coop Pronto Bahnhof', tags: ['Vacanze'] })
  const account = await apiOk<{ id: number }>(request, 'POST', '/api/positions',
    { name: 'Conto Coop', assetClass: 'CASH', currency: 'CHF', iban: 'CH9300762011623852957' })
  await apiOk(request, 'POST', '/api/recurring-entries', {
    kind: 'EXPENSE', categoryId: shop, amount: 30, currency: 'CHF', description: 'Abbonamento Coop',
    frequency: 'MONTHLY', startDate: '2099-01-01', tags: [],
  })
  await apiOk(request, 'POST', '/api/goals',
    { name: 'Vacanze Coop', kind: 'BALANCE', targetAmount: 1000, currency: 'CHF', targetDate: null, positionIds: [account.id] })

  await page.goto('/')
  await expect(page.getByRole('button', { name: 'Cerca' })).toBeVisible()
  await openSearch(page)
  const box = page.getByRole('combobox', { name: 'Cerca' })
  await box.fill('coop')
  const results = page.getByRole('listbox', { name: 'Risultati della ricerca' })
  await expect(results.getByRole('group', { name: 'Movimenti' })).toContainText('Coop Pronto Bahnhof')
  await expect(results.getByRole('group', { name: 'Posizioni' })).toContainText('Conto Coop')
  await expect(results.getByRole('group', { name: 'Ricorrenti' })).toContainText('Abbonamento Coop')
  await expect(results.getByRole('group', { name: 'Obiettivi' })).toContainText('Vacanze Coop')

  // Enter opens the first result, highlighted: the entry, in its form
  await expect(results.getByRole('option').first()).toHaveAttribute('aria-selected', 'true')
  await box.press('Enter')
  await expect(page).toHaveURL(/\/movimenti$/)
  const dialog = page.locator('dialog[open]')
  await expect(dialog.getByRole('heading', { name: 'Modifica movimento' })).toBeVisible()
  await expect(dialog.getByLabel('Descrizione (facoltativa)')).toHaveValue('Coop Pronto Bahnhof')
  await dialog.getByRole('button', { name: 'Chiudi' }).click()

  // Arrow keys down to the recurring entry; it opens on its page
  await page.getByRole('button', { name: 'Cerca' }).click()
  await box.fill('abbonamento')
  await expect(results.getByRole('option')).toHaveCount(1)
  await box.press('Enter')
  await expect(page).toHaveURL(/\/ricorrenti$/)
  await expect(dialog.getByRole('heading', { name: 'Modifica ricorrenza' })).toBeVisible()
  await dialog.getByRole('button', { name: 'Chiudi' }).click()

  // A goal, by clicking
  await openSearch(page)
  await box.fill('vacanze c')
  await results.getByRole('option', { name: /Vacanze Coop/ }).click()
  await expect(dialog.getByRole('heading', { name: 'Modifica obiettivo' })).toBeVisible()
  await dialog.getByRole('button', { name: 'Chiudi' }).click()

  // A position by part of its IBAN
  await openSearch(page)
  await box.fill('0076 2011')
  await results.getByRole('option', { name: /Conto Coop/ }).click()
  await expect(page).toHaveURL(new RegExp(`/posizioni/${account.id}$`))
  await expect(page.getByRole('heading', { name: 'Conto Coop' })).toBeVisible()

  // A tag lists its entries; an amount finds the entry
  await openSearch(page)
  await box.fill('vacanze')
  await results.getByRole('group', { name: 'Etichette' }).getByRole('option', { name: 'Vacanze' }).click()
  await expect(page).toHaveURL(/\/movimenti$/)
  await expect(page.getByLabel('Etichette', { exact: true })).toHaveValue(/\d+/)
  await expect(page.locator('tbody tr')).toHaveCount(1)
  await openSearch(page)
  await box.fill('45,20')
  await expect(results.getByRole('option')).toHaveCount(1)
  await expect(results).toContainText('Coop Pronto Bahnhof')

  // Escape closes it; nothing found says so
  await box.fill('zzz-nulla')
  await expect(page.locator('dialog[open]').getByRole('status')).toHaveText('Nessun risultato per «zzz-nulla».')
  await box.press('Escape')
  await expect(page.locator('dialog[open]')).toHaveCount(0)
})
