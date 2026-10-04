import { expect, test } from './fixtures'

test('split entry: one receipt shared among categories, edited and deleted as a whole', async ({ signedIn: page }) => {
  await page.goto('/movimenti')
  await page.getByRole('button', { name: 'Nuovo movimento' }).click()
  const dialog = page.locator('dialog[open]')
  await dialog.getByLabel('Importo', { exact: true }).fill('120')
  await dialog.getByRole('button', { name: 'Dividi in più categorie' }).click()
  await expect(dialog.getByLabel('Totale', { exact: true })).toHaveValue('120')

  await dialog.getByLabel('Categoria della parte 1').selectOption({ label: 'Spesa alimentare › Supermercato' })
  await dialog.getByLabel('Importo della parte 1').fill('90')
  await expect(dialog.getByRole('status')).toContainText('Da assegnare')
  await dialog.getByLabel('Categoria della parte 2').selectOption({ label: 'Casa › Arredamento' })
  await dialog.getByLabel('Importo della parte 2').fill('30')
  await expect(dialog.getByRole('status')).toHaveText('Totale assegnato')
  await dialog.getByLabel('Descrizione (facoltativa)').fill('Migros Zürich')
  await dialog.getByRole('button', { name: 'Salva', exact: true }).click()
  await expect(dialog).toHaveCount(0)

  // One entry per part, each in its category, marked as a part
  const rows = page.locator('tbody tr').filter({ hasText: 'Migros Zürich' })
  await expect(rows).toHaveCount(2)
  await expect(rows.filter({ hasText: 'Supermercato' })).toContainText('90.00')
  await expect(rows.filter({ hasText: 'Arredamento' })).toContainText('30.00')
  await expect(rows.first().getByLabel('Parte di un movimento diviso')).toBeVisible()

  // Opening a part opens the whole receipt: a third part
  await rows.filter({ hasText: 'Arredamento' }).getByRole('button', { name: 'Modifica' }).click()
  await expect(dialog.getByLabel('Totale', { exact: true })).toHaveValue('120')
  await dialog.getByLabel('Importo della parte 1').fill('80')
  await dialog.getByRole('button', { name: 'Aggiungi una parte' }).click()
  await dialog.getByLabel('Categoria della parte 3').selectOption({ label: 'Spesa alimentare › Negozi e mercato' })
  await dialog.getByLabel('Importo della parte 3').fill('5')
  await dialog.getByRole('button', { name: 'Salva', exact: true }).click()
  // Not balanced: 80 + 30 + 5 is not 120
  await expect(dialog).toContainText('La somma delle parti deve essere uguale al totale.')
  await dialog.getByLabel('Importo della parte 3').fill('10')
  await dialog.getByRole('button', { name: 'Salva', exact: true }).click()
  await expect(dialog).toHaveCount(0)
  await expect(rows).toHaveCount(3)

  // Deleting a part deletes the whole receipt
  page.once('dialog', (d) => d.accept())
  await rows.first().getByRole('button', { name: 'Elimina' }).click()
  await expect(rows).toHaveCount(0)
})
