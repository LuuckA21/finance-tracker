import { apiOk, expect, test } from './fixtures'

/** A camt.053 bank statement as the Swiss banks export it: salary, a premium, a pending booking. */
const STATEMENT = `<?xml version="1.0" encoding="UTF-8"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.04">
  <BkToCstmrStmt>
    <GrpHdr><MsgId>E2E</MsgId><CreDtTm>2026-10-01T06:00:00</CreDtTm></GrpHdr>
    <Stmt>
      <Id>1</Id>
      <Acct><Id><IBAN>CH9300762011623852957</IBAN></Id><Ccy>CHF</Ccy></Acct>
      <Bal><Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp><Amt Ccy="CHF">6120.35</Amt>
        <CdtDbtInd>CRDT</CdtDbtInd><Dt><Dt>2026-09-30</Dt></Dt></Bal>
      <Ntry><Amt Ccy="CHF">6000.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><Sts>BOOK</Sts>
        <BookgDt><Dt>2026-09-25</Dt></BookgDt>
        <NtryDtls><TxDtls><RltdPties><Dbtr><Nm>ACME AG</Nm></Dbtr></RltdPties>
          <RmtInf><Ustrd>Lohn September</Ustrd></RmtInf></TxDtls></NtryDtls></Ntry>
      <Ntry><Amt Ccy="CHF">520.00</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts>BOOK</Sts>
        <BookgDt><Dt>2026-09-28</Dt></BookgDt>
        <NtryDtls><TxDtls><RltdPties><Cdtr><Nm>Helsana</Nm></Cdtr></RltdPties>
          <RmtInf><Ustrd>Praemie Oktober</Ustrd></RmtInf></TxDtls></NtryDtls></Ntry>
      <Ntry><Amt Ccy="CHF">99.00</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts>PDNG</Sts>
        <BookgDt><Dt>2026-09-30</Dt></BookgDt><AddtlNtryInf>Vormerkung Online-Shop</AddtlNtryInf></Ntry>
    </Stmt>
  </BkToCstmrStmt>
</Document>`

test('bank statement (camt.053): rules categorize, the IBAN finds the account, the closing balance updates it', async ({ signedIn: page }) => {
  // A bank account with its IBAN, entered as printed
  await page.goto('/posizioni')
  await page.getByRole('button', { name: 'Nuova posizione' }).first().click()
  const form = page.locator('dialog[open]')
  await form.getByLabel('Nome').fill('Conto UBS')
  await form.getByLabel(/IBAN/).fill('ch93 0076 2011 6238 5295 7')
  await form.getByRole('button', { name: 'Salva' }).click()
  await expect(form).toHaveCount(0)

  const categories = await apiOk<{ id: number; name: string }[]>(page.request, 'GET', '/api/categories')
  const health = categories.find((c) => c.name === 'Cassa malati')!
  await apiOk(page.request, 'POST', '/api/category-rules', { pattern: 'helsana', categoryId: health.id })

  await page.goto('/movimenti')
  await page.getByRole('button', { name: 'Importa', exact: true }).click()
  const dialog = page.locator('dialog[open]')
  await dialog.getByLabel(/estratto conto/).setInputFiles({ name: 'estratto.xml', mimeType: 'application/xml', buffer: Buffer.from(STATEMENT) })
  await dialog.getByRole('button', { name: 'Analizza file' }).click()

  // The account and its closing balance, with the offer to update the position
  await expect(dialog.getByText('CH93 0076 2011 6238 5295 7')).toBeVisible()
  await expect(dialog.getByText(/Saldo finale al 30\.09\.2026/)).toBeVisible()
  const balance = dialog.getByRole('checkbox', { name: /Aggiorna il valore di «Conto UBS»/ })
  await expect(balance).toBeChecked()

  const rows = dialog.locator('tbody tr')
  await expect(rows).toHaveCount(3)
  // The rule categorizes the premium; the salary waits for a category; pending bookings stay out
  const premium = rows.filter({ hasText: 'Helsana · Praemie Oktober' })
  await expect(premium).toContainText('Regola «helsana»')
  await expect(premium.getByRole('checkbox')).toBeChecked()
  await expect(rows.filter({ hasText: 'ACME AG · Lohn September' }).getByRole('checkbox')).not.toBeChecked()
  const pending = rows.filter({ hasText: 'Vormerkung Online-Shop' })
  await expect(pending).toContainText('Non ancora contabilizzato dalla banca')
  await expect(pending.getByRole('checkbox')).toBeDisabled()

  await dialog.getByRole('button', { name: 'Importa selezionati (1)' }).click()
  await expect(dialog).toContainText('Movimenti importati: 1.')
  await expect(dialog).toContainText('Valore aggiornato con il saldo finale: Conto UBS.')

  const positions = await apiOk<{ name: string; iban: string; latest: { date: string; value: number } }[]>(page.request, 'GET', '/api/positions')
  const account = positions.find((p) => p.name === 'Conto UBS')!
  expect(account.iban).toBe('CH9300762011623852957')
  expect(account.latest).toMatchObject({ date: '2026-09-30', value: 6120.35 })
})
