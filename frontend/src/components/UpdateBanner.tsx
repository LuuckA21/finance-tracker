import { RefreshCw } from 'lucide-react'
import { useI18n } from '../i18n'
import { applyUpdate, useUpdateReady } from '../lib/pwa'
import { Button } from './ui'

/** Offers to switch to a newer build of the app, once it has been downloaded in the background. */
export function UpdateBanner() {
  const ready = useUpdateReady()
  const { t } = useI18n()
  if (!ready) return null
  return (
    <div role="status" className="fixed inset-x-3 bottom-3 z-30 mx-auto flex max-w-md items-center justify-between gap-3 rounded-lg border border-line bg-surface px-4 py-3 text-sm shadow-lg print:hidden">
      <span>{t('pwa.updateReady')}</span>
      <Button variant="primary" onClick={applyUpdate}>
        <RefreshCw className="size-4" aria-hidden /> {t('pwa.update')}
      </Button>
    </div>
  )
}
