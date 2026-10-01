import { apiOk, expect, test } from './fixtures'

test('category trend: from the income & expenses page to a macro and its details month by month', async ({ signedIn: page }) => {
  const year = new Date().getFullYear() - 1
  const categories = await apiOk<{ id: number; name: string }[]>(page.request, 'GET', '/api/categories')
  const id = (name: string) => categories.find((c) => c.name === name)!.id
  const expense = (date: string, category: string, amount: number) => apiOk(page.request, 'POST', '/api/cash-entries',
    { date, kind: 'EXPENSE', categoryId: id(category), amount, currency: 'CHF', description: category })
  await expense(`${year}-01-05`, 'Affitto', 1800)
  await expense(`${year}-01-20`, 'Energia', 120)
  await expense(`${year}-02-05`, 'Affitto', 1800)
  await expense(`${year - 1}-01-05`, 'Affitto', 1700)

  await page.goto('/flussi')
  await page.getByLabel('Anno', { exact: true }).selectOption(String(year))
  await page.getByRole('region', { name: 'Uscite per categoria' }).getByRole('link', { name: 'Andamento di Casa' }).click()

  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Andamento: Casa')
  await expect(page.getByText(`Totale ${year}`, { exact: true }).first()).toBeVisible()
  const monthly = page.getByRole('region', { name: 'Tabella mensile' })
  await expect(monthly.getByRole('row').filter({ hasText: 'gennaio' })).toContainText(/CHF\s?1.?920/)
  await expect(monthly.getByRole('row').filter({ hasText: 'gennaio' })).toContainText(/CHF\s?1.?700/)
  await expect(monthly.getByRole('row').filter({ hasText: 'Totale' })).toContainText(/CHF\s?3.?720/)

  // The details, each one its own trend
  const details = page.getByRole('region', { name: 'Per dettaglio' })
  await expect(details.getByRole('row').filter({ hasText: 'Affitto' })).toContainText(/CHF\s?3.?600/)
  await details.getByRole('link', { name: 'Affitto' }).click()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Andamento: Casa › Affitto')
  await expect(page.getByRole('region', { name: 'Tabella mensile' }).getByRole('row').filter({ hasText: 'Totale' }))
    .toContainText(/CHF\s?3.?600/)

  // Another year from the same page
  await page.getByLabel('Anno', { exact: true }).selectOption(String(year - 1))
  await expect(page.getByRole('region', { name: 'Tabella mensile' }).getByRole('row').filter({ hasText: 'Totale' }))
    .toContainText(/CHF\s?1.?700/)

  // It fits a phone screen
  await page.setViewportSize({ width: 360, height: 800 })
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(360)
})
