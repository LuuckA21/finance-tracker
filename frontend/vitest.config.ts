import { defineConfig } from 'vitest/config'

// Unit tests for pure frontend code (formatting, i18n, API errors); the pages are covered by the
// Playwright suite in e2e/.
export default defineConfig({
  test: {
    include: ['src/**/*.test.ts'],
    setupFiles: ['src/test/setup.ts'],
    environment: 'node',
  },
})
