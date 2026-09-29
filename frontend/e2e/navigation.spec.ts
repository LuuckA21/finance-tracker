import { expect, test } from './fixtures'

test('the menu goes from the overview through cash flow and net worth to the report and settings', async ({ signedIn: page }) => {
  await page.goto('/')
  const menu = page.getByRole('navigation', { name: 'Navigazione principale' })
  await expect(menu.getByRole('link')).toHaveText([
    'Panoramica',
    'Movimenti', 'Ricorrenti', 'Budget', 'Entrate e uscite', 'Previsione',
    'Posizioni', 'Aggiorna valori', 'Obiettivi', 'Patrimonio',
    'Riepilogo annuale',
    'Impostazioni',
  ])
  // Groups are separated, the separators stay out of the accessibility tree
  await expect(menu.locator('hr')).toHaveCount(4)
})
