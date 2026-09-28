import { request, type FullConfig } from '@playwright/test'
import { adminUser, apiLogin, apiOk } from './fixtures'

/**
 * On an empty database (CI) the bootstrap administrator must still replace its configured
 * password: do it once here and hand the new one to the workers.
 */
export default async function globalSetup(config: FullConfig) {
  const admin = adminUser()
  const api = await request.newContext({ baseURL: config.projects[0].use.baseURL })
  await apiLogin(api, admin)
  const me = await apiOk<{ passwordChangeRequired: boolean }>(api, 'GET', '/api/auth/me')
  if (me.passwordChangeRequired) {
    const password = `${admin.password}-e2e-${Date.now()}`
    await apiOk(api, 'PUT', '/api/account/password', { currentPassword: admin.password, newPassword: password })
    process.env.E2E_ADMIN_PASSWORD = password
  }
  await api.dispose()
}
