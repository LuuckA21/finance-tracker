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
  await page.getByRole('button', { name: 'Importa CSV' }).click()
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
    await page.getByRole('button', { name: 'Importa CSV' }).click()
    const dialog = page.locator('dialog[open]')
    await dialog.getByLabel('File CSV').setInputFiles(file)
    await dialog.getByRole('radio', { name: 'Importa tutto' }).click()
    await dialog.getByRole('button', { name: 'Importa', exact: true }).click()
    await expect(dialog.getByText(expected)).toBeVisible()
    await dialog.getByRole('button', { name: 'Chiudi' }).first().click()
  }
  await expect(page.locator('tbody tr')).toHaveCount(1)
})
