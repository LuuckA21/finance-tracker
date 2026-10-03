// Service worker of the installed app (built into dist/sw.js by pwa/plugin.ts, which fills in the
// version and the files of the build).
//
// It keeps the app itself (HTML, scripts, styles, icons) so that it opens at once, even offline,
// and NEVER the data: requests to /api/ are left to the browser and the server, so nothing about
// the user's finances is ever stored on the device by it. A new build installs in the background
// and waits; the page offers to switch (see src/lib/pwa.ts), and the old files go with it.

const VERSION = '__VERSION__'
const PRECACHE = __PRECACHE__
const CACHE = `finanze-${VERSION}`
// Each URL holds one fixed file: ignore Vary (module scripts are requested with an Origin header)
const MATCH = { cacheName: CACHE, ignoreVary: true }

self.addEventListener('install', (event) => {
  // Fresh copies, not whatever the HTTP cache holds
  event.waitUntil(caches.open(CACHE).then((cache) =>
    cache.addAll(PRECACHE.map((url) => new Request(url, { cache: 'reload' })))))
})

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    for (const key of await caches.keys()) {
      if (key.startsWith('finanze-') && key !== CACHE) await caches.delete(key)
    }
    await self.clients.claim()
  })())
})

// The page asks to switch to this version once the user agrees
self.addEventListener('message', (event) => {
  if (event.data === 'skip-waiting') self.skipWaiting()
})

self.addEventListener('fetch', (event) => {
  const request = event.request
  if (request.method !== 'GET') return
  const url = new URL(request.url)
  if (url.origin !== self.location.origin) return
  // The data never goes through here
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/actuator/')) return

  if (request.mode === 'navigate') {
    // Pages: the server's current version when it can be reached, otherwise the kept app
    event.respondWith(fetch(request).catch(async () =>
      (await caches.match('/index.html', MATCH)) ?? Response.error()))
    return
  }
  // Files of the build: from the cache, anything else from the network (and not kept)
  event.respondWith(caches.match(request, MATCH).then((hit) => hit ?? fetch(request)))
})
