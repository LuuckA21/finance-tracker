import { readFile } from 'node:fs/promises'
import { expect, test } from './fixtures'

const CSV = [
  'data;tipo;categoria;importo;valuta;descrizione',
  '01.08.2026;Uscita;Spesa alimentare;45,20;CHF;Migros',
  '02.08.2026;Uscita;Ristorazione;32.50;CHF;Pizzeria',
  '03.08.2026;Entrata;Stipendio;6000;CHF;Stipendio agosto',
  '31.02.2026;Uscita;Svago;10;CHF;Data impossibile',
].join('\r\n')

test('import row by row: fix a category, skip a broken row, then export', async ({ signedIn: page }) => {
  await page.goto('/movimenti')
  await page.getByRole('button', { name: 'Importa', exact: true }).click()
  const dialog = page.locator('dialog[open]')
  await dialog.getByLabel('File CSV').setInputFiles({ name: 'movimenti.csv', mimeType: 'text/csv', buffer: Buffer.from(CSV) })
  await dialog.getByRole('radio', { name: 'Rivedi riga per riga' }).click()
  await dialog.getByRole('button', { name: 'Analizza file' }).click()

  await expect(dialog.getByText('Righe: 4')).toBeVisible()
  await expect(dialog.getByText('Valide: 2')).toBeVisible()
  await expect(dialog.getByText('Da sistemare: 2')).toBeVisible()

  // "Ristorazione" does not exist: choose one of the user's categories
  const pizzeria = dialog.locator('tbody tr').filter({ hasText: 'Pizzeria' })
  await expect(pizzeria).toContainText('Nel file: Ristorazione')
  await pizzeria.getByLabel('Categoria').selectOption({ label: 'Ristoranti' })
  await expect(pizzeria.getByRole('checkbox')).toBeChecked()
  // A date that does not exist cannot be fixed here: the row stays out
  await expect(dialog.locator('tbody tr').filter({ hasText: 'Data impossibile' }).getByRole('checkbox')).toBeDisabled()

  await dialog.getByRole('button', { name: 'Importa selezionati (3)' }).click()
  await expect(dialog.getByText('Movimenti importati: 3.')).toBeVisible()
  await dialog.getByRole('button', { name: 'Chiudi' }).first().click()

  const rows = page.locator('tbody tr')
  await expect(rows).toHaveCount(3)
  await expect(rows.filter({ hasText: 'Pizzeria' })).toContainText('Ristoranti')

  // Export: the same rows, Excel-friendly
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Esporta CSV' }).click()
  const file = await download
  expect(file.suggestedFilename()).toMatch(/^movimenti-\d{4}-\d{2}-\d{2}\.csv$/)
  const csv = await readFile((await file.path())!, 'utf8')
  expect(csv.startsWith('\uFEFFdata;tipo;categoria;sottocategoria;importo;valuta;descrizione;da;verso;etichette\r\n')).toBe(true)
  expect(csv).toContain('2026-08-02;Uscita;Ristoranti;;32.5;CHF;Pizzeria;;')
  expect(csv).toContain('2026-08-03;Entrata;Stipendio;;6000;CHF;Stipendio agosto;;')
})

test('importing the same file twice finds only duplicates', async ({ signedIn: page }) => {
  const file = { name: 'movimenti.csv', mimeType: 'text/csv', buffer: Buffer.from(CSV.split('\r\n').slice(0, 2).join('\r\n')) }
  await page.goto('/movimenti')
  for (const expected of ['Movimenti importati: 1.', 'Nessun movimento nuovo']) {
    await page.getByRole('button', { name: 'Importa', exact: true }).click()
    const dialog = page.locator('dialog[open]')
    await dialog.getByLabel('File CSV').setInputFiles(file)
    await dialog.getByRole('radio', { name: 'Importa tutto' }).click()
    await dialog.getByRole('button', { name: 'Importa', exact: true }).click()
    await expect(dialog.getByText(expected)).toBeVisible()
    await dialog.getByRole('button', { name: 'Chiudi' }).first().click()
  }
  await expect(page.locator('tbody tr')).toHaveCount(1)
})

const NEW_CATEGORIES = [
  'data;tipo;categoria;sottocategoria;importo;valuta;descrizione',
  '04.08.2026;Uscita;Ristorazione;;32.50;CHF;Pizzeria',
  '05.08.2026;Uscita;ristorazione;;18;CHF;Kebab',
  '06.08.2026;Uscita;Casa;Garage;150;CHF;Box auto',
  '07.08.2026;Uscita;Animali;Veterinario;90;CHF;Visita gatto',
].join('\r\n')

test('import row by row: create the categories the file names on the spot', async ({ signedIn: page }) => {
  await page.goto('/movimenti')
  await page.getByRole('button', { name: 'Importa', exact: true }).click()
  const dialog = page.locator('dialog[open]')
  await dialog.getByLabel('File CSV').setInputFiles({ name: 'nuove.csv', mimeType: 'text/csv', buffer: Buffer.from(NEW_CATEGORIES) })
  await dialog.getByRole('radio', { name: 'Rivedi riga per riga' }).click()
  await dialog.getByRole('button', { name: 'Analizza file' }).click()

  const missing = dialog.getByRole('region', { name: 'Categorie non trovate: 3' })
  const item = (name: string) => missing.getByRole('listitem').filter({ hasText: name })

  // A new macro, for both rows that name it (case aside)
  await item('Ristorazione').getByRole('button', { name: 'Crea…' }).click()
  await expect(missing.getByLabel('Nome')).toHaveValue('Ristorazione')
  await expect(missing.getByLabel('Macro categoria')).toHaveValue('')
  await missing.getByRole('button', { name: 'Crea e assegna' }).click()
  await expect(dialog.locator('tbody tr').filter({ hasText: 'Kebab' }).getByLabel('Categoria')).toHaveValue(/\d+/)
  await expect(dialog.locator('tbody tr').filter({ hasText: 'Pizzeria' }).getByRole('checkbox')).toBeChecked()

  // A detail under a macro the user has
  const left = dialog.getByRole('region', { name: 'Categorie non trovate: 2' })
  await left.getByRole('listitem').filter({ hasText: 'Casa › Garage' }).getByRole('button', { name: 'Crea…' }).click()
  await expect(left.getByLabel('Macro categoria').locator('option:checked')).toHaveText('Casa')
  await left.getByRole('button', { name: 'Crea e assegna' }).click()

  // A new macro with its detail
  const last = dialog.getByRole('region', { name: 'Categorie non trovate: 1' })
  await last.getByRole('button', { name: 'Crea…' }).click()
  await expect(last.getByLabel('Nome')).toHaveValue('Veterinario')
  await expect(last.getByLabel('Macro categoria').locator('option:checked')).toHaveText('Nuova macro «Animali»')
  await last.getByRole('button', { name: 'Crea e assegna' }).click()
  await expect(dialog.getByRole('region', { name: 'Serve una categoria nuova?' })).toBeVisible()

  await dialog.getByRole('button', { name: 'Importa selezionati (4)' }).click()
  await expect(dialog.getByText('Movimenti importati: 4.')).toBeVisible()
  await dialog.getByRole('button', { name: 'Chiudi' }).first().click()

  const rows = page.locator('tbody tr')
  await expect(rows.filter({ hasText: 'Kebab' })).toContainText('Ristorazione')
  await expect(rows.filter({ hasText: 'Box auto' })).toContainText('Casa › Garage')
  await expect(rows.filter({ hasText: 'Visita gatto' })).toContainText('Animali › Veterinario')
})
