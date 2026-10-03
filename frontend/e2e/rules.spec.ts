import { apiOk, expect, test } from './fixtures'

test('category rules: create one, get suggestions while writing, and categorize an import', async ({ signedIn: page }) => {
  // A rule from the settings
  await page.goto('/impostazioni/regole')
  await expect(page.getByText('Nessuna regola')).toBeVisible()
  const form = page.getByRole('region', { name: 'Nuova regola' })
  await form.getByLabel('La descrizione contiene').fill('migros')
  await form.getByLabel('Categoria').selectOption({ label: 'Spesa alimentare › Supermercato' })
  await form.getByRole('button', { name: 'Aggiungi regola' }).click()
  const list = page.getByRole('region', { name: 'Regole: 1' })
  await expect(list).toContainText('«migros»')
  await expect(list).toContainText('Spesa alimentare › Supermercato')

  // A past entry for the history
  const categories = await apiOk<{ id: number; name: string }[]>(page.request, 'GET', '/api/categories')
  const dinners = categories.find((c) => c.name === 'Cene')!.id
  await apiOk(page.request, 'POST', '/api/cash-entries', {
    date: '2026-07-01', kind: 'EXPENSE', categoryId: dinners, amount: 80, currency: 'CHF', description: 'Pizzeria Da Mario',
  })

  // While writing an entry: the rule, then the past
  await page.goto('/movimenti')
  await page.getByRole('button', { name: 'Nuovo movimento' }).click()
  const dialog = page.locator('dialog[open]')
  await dialog.getByLabel('Importo').fill('45')
  await dialog.getByLabel('Descrizione (facoltativa)').fill('MIGROS Bern 1234')
  await expect(dialog.getByText('Suggerita dalla regola «migros»')).toBeVisible()
  await expect(dialog.getByLabel('Categoria').locator('option:checked')).toHaveText('Spesa alimentare › Supermercato')
  await dialog.getByLabel('Descrizione (facoltativa)').fill('pizzeria da mario 15.09')
  await expect(dialog.getByText('Suggerita dai movimenti passati con la stessa descrizione')).toBeVisible()
  await expect(dialog.getByLabel('Categoria').locator('option:checked')).toHaveText('Ristoranti › Cene')
  await dialog.getByRole('button', { name: 'Salva', exact: true }).click()
  await expect(dialog).toBeHidden()
  await expect(page.locator('tbody tr').filter({ hasText: 'pizzeria da mario 15.09' })).toContainText('Ristoranti › Cene')

  // An import: the rule applies, the past proposes, a new rule categorizes the other rows it matches
  const csv = [
    'data;tipo;categoria;importo;valuta;descrizione',
    '01.08.2026;Uscita;;52.30;CHF;MIGROS ZURICH 4411',
    '02.08.2026;Uscita;;64;CHF;Pizzeria Da Mario',
    '03.08.2026;Uscita;;4.50;CHF;Edicola Centrale 12',
    '04.08.2026;Uscita;;3.20;CHF;EDICOLA STAZIONE',
  ].join('\r\n')
  await page.getByRole('button', { name: 'Importa', exact: true }).click()
  await dialog.getByLabel('File CSV').setInputFiles({ name: 'banca.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) })
  await dialog.getByRole('radio', { name: 'Rivedi riga per riga' }).click()
  await dialog.getByRole('button', { name: 'Analizza file' }).click()

  const row = (text: string) => dialog.locator('tbody tr').filter({ hasText: text })
  await expect(row('MIGROS ZURICH')).toContainText('Regola «migros»')
  await expect(row('MIGROS ZURICH').getByRole('checkbox')).toBeChecked()
  await expect(row('Pizzeria Da Mario')).toContainText('Proposta dallo storico')
  await expect(row('Pizzeria Da Mario').getByRole('checkbox')).not.toBeChecked()
  await row('Pizzeria Da Mario').getByRole('checkbox').check()

  await row('Edicola Centrale').getByLabel('Categoria').selectOption({ label: 'Svago › Cultura' })
  await row('Edicola Centrale').getByRole('button', { name: 'Crea regola' }).click()
  await expect(dialog.getByLabel('La descrizione contiene')).toHaveValue('edicola centrale')
  await dialog.getByLabel('La descrizione contiene').fill('edicola')
  await dialog.getByRole('button', { name: 'Aggiungi regola' }).click()
  await expect(row('EDICOLA STAZIONE').getByLabel('Categoria').locator('option:checked')).toHaveText('Svago › Cultura')
  await expect(row('EDICOLA STAZIONE').getByRole('checkbox')).toBeChecked()

  await dialog.getByRole('button', { name: 'Importa selezionati (4)' }).click()
  await expect(dialog.getByText('Movimenti importati: 4.')).toBeVisible()
  await dialog.getByRole('button', { name: 'Chiudi' }).first().click()

  await page.goto('/impostazioni/regole')
  await expect(page.getByRole('region', { name: 'Regole: 2' })).toContainText('«edicola»')
})
