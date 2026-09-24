import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// In development the Spring backend runs on :8080; proxying keeps the app same-origin,
// which the SameSite=Strict session cookie and CSRF protection rely on.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  build: {
    sourcemap: false,
    chunkSizeWarningLimit: 1000,
  },
})
