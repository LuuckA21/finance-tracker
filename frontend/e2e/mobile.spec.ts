import { apiOk, expect, expectNoHorizontalScroll, test, today } from './fixtures'

test.use({ viewport: { width: 360, height: 780 }, isMobile: true, hasTouch: true })

const PAGES = [
  { path: '/', heading: /^Ciao / },
  { path: '/movimenti', heading: 'Movimenti' },
  { path: '/ricorrenti', heading: 'Movimenti ricorrenti' },
  { path: '/flussi', heading: 'Entrate e uscite' },
  { path: '/budget', heading: 'Budget' },
  { path: '/obiettivi', heading: 'Obiettivi di risparmio' },
  { path: '/patrimonio', heading: 'Patrimonio' },
  { path: '/posizioni', heading: 'Posizioni' },
  { path: '/aggiorna', heading: 'Aggiorna valori' },
  { path: '/impostazioni', heading: 'Impostazioni' },
  { path: '/impostazioni/categorie', heading: 'Impostazioni' },
  { path: '/impostazioni/cambi', heading: 'Impostazioni' },
]

test('every page fits a 360 px phone screen', async ({ signedIn: page }) => {
  // Enough data for the tables and charts to render
  const request = page.request
  const categories = await apiOk<{ id: number; name: string }[]>(request, 'GET', '/api/categories')
  const id = (name: string) => categories.find((c) => c.name === name)!.id
  const position = await apiOk<{ id: number }>(request, 'POST', '/api/positions',
    { name: 'Conto risparmio con un nome piuttosto lungo', symbol: '', assetClass: 'CASH', currency: 'CHF', notes: '', archived: false })
  await apiOk(request, 'POST', `/api/positions/${position.id}/snapshots`, { date: today(), quantity: 1, unitPrice: 12500, note: '' })
  for (const entry of [
    { kind: 'EXPENSE', categoryId: id('Spesa alimentare'), amount: 84.35, description: 'Spesa settimanale al supermercato' },
    { kind: 'INCOME', categoryId: id('Stipendio'), amount: 6400, description: 'Stipendio' },
    { kind: 'TRANSFER', categoryId: null, toPositionId: position.id, amount: 500, description: 'Risparmio' },
  ]) {
    await apiOk(request, 'POST', '/api/cash-entries', { date: today(), currency: 'CHF', ...entry })
  }
  await apiOk(request, 'POST', '/api/recurring-entries', {
    kind: 'EXPENSE', categoryId: id('Casa'), fromPositionId: null, toPositionId: null, amount: 1850, currency: 'CHF',
    description: 'Affitto', frequency: 'MONTHLY', startDate: today(), endDate: null, active: true,
  })
  await apiOk(request, 'PUT', `/api/budgets/${id('Spesa alimentare')}`, { amount: 600, currency: 'CHF' })
  await apiOk(request, 'POST', '/api/goals', {
    name: 'Fondo emergenza con un nome piuttosto lungo', kind: 'BALANCE', targetAmount: 30000, currency: 'CHF',
    targetDate: null, positionIds: [position.id],
  })

  for (const { path, heading } of PAGES) {
    await page.goto(path)
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(heading)
    await page.waitForLoadState('networkidle')
    await expectNoHorizontalScroll(page)
  }

  // Position detail, reached from the list
  await page.goto('/posizioni')
  await page.getByRole('link', { name: /Conto risparmio/ }).click()
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Conto risparmio')
  await page.waitForLoadState('networkidle')
  await expectNoHorizontalScroll(page)

  // The navigation lives behind the menu button
  await page.getByRole('button', { name: 'Apri menu' }).click()
  await page.getByRole('link', { name: 'Budget' }).click()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Budget')
})
