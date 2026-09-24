import { ShieldAlert } from 'lucide-react'
import { ChangePasswordForm } from '../pages/settings/ChangePasswordForm'
import { useLogout } from './useLogout'
import { Button } from '../components/ui'
import { useI18n } from '../i18n'

/** Shown instead of the app while the account still has a temporary password. */
export function ForcePasswordChangePage() {
  const logout = useLogout()
  const { t } = useI18n()
  return (
    <main className="flex min-h-dvh items-center justify-center px-4">
      <div className="card w-full max-w-md p-6">
        <div className="mb-5 flex items-start gap-3">
          <div className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-warn-soft text-warn-ink">
            <ShieldAlert className="size-5" />
          </div>
          <div>
            <h1 className="text-lg font-semibold">{t('forcePassword.title')}</h1>
            <p className="text-sm text-ink-2">
              {t('forcePassword.help')}
            </p>
          </div>
        </div>
        <ChangePasswordForm />
        <Button variant="ghost" className="mt-3 w-full" onClick={logout}>{t('nav.logout')}</Button>
      </div>
    </main>
  )
}
