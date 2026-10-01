import { readFileSync } from 'node:fs'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

const { version } = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')) as { version: string }

// In development the Spring backend runs on :8080; proxying keeps the app same-origin,
// which the SameSite=Strict session cookie and CSRF protection rely on.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  // The release shown in the menu: the version in package.json (the same as the backend's pom.xml)
  define: {
    'import.meta.env.APP_VERSION': JSON.stringify(version),
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  build: {
    sourcemap: false,
  },
})
