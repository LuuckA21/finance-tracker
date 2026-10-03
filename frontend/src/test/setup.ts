import { beforeEach } from 'vitest'
import { setLanguage } from '../i18n'

// setLanguage() updates <html lang>, the page title and the manifest link: a minimal document
// (without the link) is enough here
globalThis.document ??= { documentElement: {}, title: '', querySelector: () => null } as unknown as Document

// Every test starts in Italian, the reference language
beforeEach(async () => {
  await setLanguage('IT')
})
