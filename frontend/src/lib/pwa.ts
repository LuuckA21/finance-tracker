import { useSyncExternalStore } from 'react'

/**
 * Registers the service worker of the installed app (pwa/service-worker.js, production builds
 * only) and tells the page when a newer build has been installed in the background, so the user
 * can switch to it with one click. An installed app can stay open for days: it checks for a new
 * build whenever it comes back to the foreground, at most every ten minutes, and every hour.
 */

const CHECK_EVERY_MS = 60 * 60_000
const MIN_GAP_MS = 10 * 60_000

let waiting: ServiceWorker | null = null
const listeners = new Set<() => void>()

function setWaiting(worker: ServiceWorker | null) {
  waiting = worker
  listeners.forEach((l) => l())
}

export function registerServiceWorker() {
  if (!import.meta.env.PROD || !('serviceWorker' in navigator)) return
  window.addEventListener('load', async () => {
    let registration: ServiceWorkerRegistration | undefined
    try {
      registration = await navigator.serviceWorker.register('/sw.js')
    } catch {
      // the app works without it
    }
    if (!registration) return
    // A build installed earlier and still waiting (only when an older one controls this page)
    if (registration.waiting && navigator.serviceWorker.controller) setWaiting(registration.waiting)
    registration.addEventListener('updatefound', () => {
      const worker = registration.installing
      worker?.addEventListener('statechange', () => {
        if (worker.state === 'installed' && navigator.serviceWorker.controller) setWaiting(worker)
      })
    })

    let lastCheck = Date.now()
    const check = () => {
      if (Date.now() - lastCheck < MIN_GAP_MS) return
      lastCheck = Date.now()
      registration.update().catch(() => {})
    }
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') check()
    })
    setInterval(check, CHECK_EVERY_MS)
  })
}

/** Switches to the waiting build and reloads the page on it. */
export function applyUpdate() {
  if (!waiting) return
  navigator.serviceWorker.addEventListener('controllerchange', () => window.location.reload(), { once: true })
  // A service worker's postMessage takes no target origin (that is window.postMessage)
  // oxlint-disable-next-line unicorn/require-post-message-target-origin
  waiting.postMessage('skip-waiting')
}

/** Whether a newer build is ready to be switched to. */
export function useUpdateReady(): boolean {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    () => waiting !== null,
  )
}
