import { beforeEach } from 'vitest'
import { setLanguage } from '../i18n'

// setLanguage() updates <html lang> and the page title: a minimal document is enough here
globalThis.document ??= { documentElement: {}, title: '' } as Document

// Every test starts in Italian, the reference language
beforeEach(async () => {
  await setLanguage('IT')
})
