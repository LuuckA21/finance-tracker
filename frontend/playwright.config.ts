import { defineConfig, devices } from '@playwright/test'

/**
 * End-to-end tests: the production build (vite preview, which proxies /api like the dev server)
 * against a real backend and PostgreSQL. Every test creates its own user through the admin API,
 * so the suite also runs against a database that already has data.
 *
 * Needs the backend on :8080 (started here from backend/target when nothing listens there, sending its
 * email to the SMTP sink on :2525) and the bootstrap administrator's credentials in
 * E2E_ADMIN_USERNAME / E2E_ADMIN_PASSWORD.
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
      // Receives the notification emails (e2e/mail.ts reads them)
      command: 'node e2e/smtp-sink.mjs',
      port: 2525,
      reuseExistingServer: !CI,
      timeout: 10_000,
    },
    {
      command: 'sh -c "java -jar ../backend/target/finance-tracker-*.jar"',
      // TZ: the zone of the browser above and of docker-compose, so "today" is the same day for the
      // backend and the tests also between 22:00 and midnight UTC
      env: { TZ: 'Europe/Zurich', MAIL_HOST: '127.0.0.1', MAIL_PORT: '2525', MAIL_SECURITY: 'NONE', MAIL_FROM: 'Finanze <e2e@example.test>',
        // Passkeys are bound to the address the browser opens (http is allowed on localhost)
        APP_PUBLIC_URL: 'http://localhost:4173' },
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
