import { createHash } from 'node:crypto'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import type { Plugin } from 'vite'
import { de } from '../src/i18n/de'
import { en } from '../src/i18n/en'
import type { Language, Messages } from '../src/i18n'
import { fr } from '../src/i18n/fr'
import { it } from '../src/i18n/it'

/**
 * Makes the app installable (a PWA): a web app manifest per interface language, named in it and
 * with its shortcuts, and in the build a service worker that keeps the files of that build (see
 * service-worker.js). The page links the manifest of the user's language (src/i18n/index.ts).
 */

const CATALOGS: Record<Language, Messages> = { IT: it, EN: en, DE: de, FR: fr }

/** Background of the page in the light theme (--page in index.css), shown while the app starts. */
const BACKGROUND = '#f5f5f3'

export function manifestFileName(language: Language) {
  return `manifest-${language.toLowerCase()}.webmanifest`
}

export function manifest(language: Language) {
  const t = CATALOGS[language]
  const icon = { src: '/icons/icon-192.png', sizes: '192x192', type: 'image/png' }
  return {
    // The same app whatever the language of the manifest that installed it
    id: '/',
    name: t['app.name'],
    short_name: t['app.name'],
    description: t['pwa.description'],
    lang: language.toLowerCase(),
    start_url: '/',
    scope: '/',
    display: 'standalone',
    background_color: BACKGROUND,
    theme_color: BACKGROUND,
    icons: [
      icon,
      { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png' },
      { src: '/icons/icon-maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
    ],
    shortcuts: [
      { name: t['entries.new'], url: '/movimenti?new=1', icons: [icon] },
      { name: t['nav.entries'], url: '/movimenti', icons: [icon] },
      { name: t['nav.budget'], url: '/budget', icons: [icon] },
    ],
  }
}

function manifests() {
  return (Object.keys(CATALOGS) as Language[]).map((language) => ({
    fileName: manifestFileName(language),
    source: JSON.stringify(manifest(language), null, 2),
  }))
}

/** Every file under `dir`, as URL paths relative to it. */
function filesUnder(dir: string): string[] {
  return readdirSync(dir, { recursive: true, encoding: 'utf8' })
    .filter((name) => statSync(join(dir, name)).isFile())
    .map((name) => relative(dir, join(dir, name)).split(sep).join('/'))
}

export function pwa(): Plugin {
  let publicDir = ''
  return {
    name: 'finanze-pwa',
    // After Vite's own plugins, so index.html is in the bundle
    enforce: 'post',
    configResolved(config) {
      publicDir = config.publicDir
    },
    // Development serves the manifests too (no service worker there: it would get in the way)
    configureServer(server) {
      const files = new Map(manifests().map((m) => [`/${m.fileName}`, m.source]))
      server.middlewares.use((req, res, next) => {
        const source = req.url ? files.get(req.url) : undefined
        if (!source) return next()
        res.setHeader('Content-Type', 'application/manifest+json')
        res.end(source)
      })
    },
    generateBundle(_, bundle) {
      const manifestFiles = manifests()
      for (const { fileName, source } of manifestFiles) {
        this.emitFile({ type: 'asset', fileName, source })
      }
      const publicFiles = publicDir ? filesUnder(publicDir) : []
      const files = new Set(['index.html', ...Object.keys(bundle), ...publicFiles, ...manifestFiles.map((m) => m.fileName)])
      const precache = [...files]
        .filter((name) => !name.endsWith('.map') && name !== 'sw.js')
        .toSorted()
        .map((name) => `/${name}`)
      // A new build is a new service worker: the browser notices the change and installs it.
      // Built files carry a hash in their names; public files and manifests count by content.
      const hash = createHash('sha256').update(precache.join('\n'))
      for (const name of publicFiles) hash.update(readFileSync(join(publicDir, name)))
      for (const { source } of manifestFiles) hash.update(source)
      const version = hash.digest('hex').slice(0, 12)
      const template = readFileSync(new URL('./service-worker.js', import.meta.url), 'utf8')
      this.emitFile({
        type: 'asset',
        fileName: 'sw.js',
        source: template
          .replace("'__VERSION__'", JSON.stringify(version))
          .replace('__PRECACHE__', JSON.stringify(precache)),
      })
    },
  }
}
