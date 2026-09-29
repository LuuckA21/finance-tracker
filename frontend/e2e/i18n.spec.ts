import { expect, test, type Language } from './fixtures'

const LANGUAGES: { code: Language; name: string; label: string; settings: string; entries: string; newEntry: string }[] = [
  { code: 'DE', name: 'Deutsch', label: 'Sprache', settings: 'Einstellungen', entries: 'Buchungen', newEntry: 'Neue Buchung' },
  { code: 'FR', name: 'Français', label: 'Langue', settings: 'Paramètres', entries: 'Opérations', newEntry: 'Nouvelle opération' },
  { code: 'EN', name: 'English', label: 'Language', settings: 'Settings', entries: 'Transactions', newEntry: 'New transaction' },
  { code: 'IT', name: 'Italiano', label: 'Lingua', settings: 'Impostazioni', entries: 'Movimenti', newEntry: 'Nuovo movimento' },
]

/** Words that only appear when a message key is shown instead of its text. */
const RAW_KEY = /\b(common|nav|entries|import|budget|fx|mfa|admin|settings|cashflow|netWorth|positions?|recurring|error|transfer|overview)\.[a-zA-Z_]+/

test('the interface switches language, and the choice survives a reload', async ({ signedIn: page }) => {
  await page.goto('/impostazioni')
  let label = 'Lingua'
  for (const language of LANGUAGES) {
    // The language is shown at once and saved in the background: reload only once it is saved
    const saved = page.waitForResponse((r) => r.url().endsWith('/api/account/settings') && r.request().method() === 'PUT')
    await page.getByLabel(label, { exact: true }).selectOption({ label: language.name })
    expect((await saved).ok()).toBe(true)
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(language.settings)
    await expect(page.locator('html')).toHaveAttribute('lang', language.code.toLowerCase())
    label = language.label

    await page.reload()
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(language.settings)

    await page.getByRole('link', { name: language.entries, exact: true }).click()
    await expect(page.getByRole('button', { name: language.newEntry })).toBeVisible()
    expect(await page.locator('body').innerText()).not.toMatch(RAW_KEY)
    await page.goto('/impostazioni')
  }
})

test.describe('a user created in French', () => {
  test.use({ language: 'FR' })

  test('starts with French interface and categories', async ({ signedIn: page }) => {
    await page.goto('/impostazioni/categorie')
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Paramètres')
    await expect(page.getByText('Alimentation')).toBeVisible()
    await expect(page.getByText('Salaire')).toBeVisible()
  })
})
