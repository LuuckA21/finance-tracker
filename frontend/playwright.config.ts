import { defineConfig, devices } from '@playwright/test'

/**
 * End-to-end tests: the production build (vite preview, which proxies /api like the dev server)
 * against a real backend and PostgreSQL. Every test creates its own user through the admin API,
 * so the suite also runs against a database that already has data.
 *
 * Needs the backend on :8080 (started here from backend/target when nothing listens there) and the
 * bootstrap administrator's credentials in E2E_ADMIN_USERNAME / E2E_ADMIN_PASSWORD.
 */
const CI = !!process.env.CI

export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  timeout: 30_000,
  expect: { timeout: 7_000 },
  fullyParallel: true,
  workers: CI ? 2 : undefined,
  forbidOnly: CI,
  retries: 0,
  reporter: CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://localhost:4173',
    locale: 'it-CH',
    timezoneId: 'Europe/Zurich',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    {
      command: 'sh -c "java -jar ../backend/target/finance-tracker-*.jar"',
      url: 'http://localhost:8080/actuator/health',
      reuseExistingServer: true,
      timeout: 120_000,
      stdout: 'ignore',
      stderr: 'pipe',
    },
    {
      command: 'npm run preview -- --port 4173 --strictPort',
      url: 'http://localhost:4173',
      reuseExistingServer: !CI,
      timeout: 30_000,
    },
  ],
})
