import type { Goal, GoalState } from '../api/types'
import { useI18n } from '../i18n'
import { date, money, monthName, percent, today } from '../lib/format'

const BAR: Record<GoalState, string> = {
  REACHED: 'bg-good',
  ON_TRACK: 'bg-accent',
  IN_PROGRESS: 'bg-accent',
  BEHIND: 'bg-warn-ink',
  NO_RATE: 'bg-muted',
}

const BADGE: Partial<Record<GoalState, string>> = {
  REACHED: 'bg-surface-2 text-good',
  ON_TRACK: 'bg-accent-soft text-accent',
  BEHIND: 'bg-warn-soft text-warn-ink',
}

/** "settembre 2028" from a yyyy-MM-dd date. */
function monthYear(iso: string) {
  const [y, m] = iso.split('-').map(Number)
  return `${monthName(m - 1)} ${y}`
}

/** Progress bar of a goal and, unless compact, what it takes to reach it. */
export function GoalProgress({ goal, compact = false }: { goal: Goal; compact?: boolean }) {
  const { t } = useI18n()
  const currency = goal.baseCurrency
  const width = Math.min(goal.percent ?? 0, 100)
  const badge = BADGE[goal.state]
  const deadlinePassed = goal.targetDate !== null && goal.targetDate < today() && goal.state !== 'REACHED'

  const details: string[] = []
  if (goal.state === 'NO_RATE') {
    details.push(t('goals.noRate'))
  } else if (goal.kind === 'BALANCE') {
    if (goal.state !== 'REACHED') {
      if (deadlinePassed) details.push(t('goals.deadlinePassed', { date: date(goal.targetDate) }))
      else if (goal.targetDate && goal.requiredMonthly !== null) {
        details.push(t('goals.required', { amount: money(goal.requiredMonthly, currency, 0), date: date(goal.targetDate) }))
      }
      if (goal.monthlyPace === null) details.push(t('goals.noPace'))
      else {
        details.push(t('goals.pace', { amount: money(goal.monthlyPace, currency, 0) }))
        if (goal.projectedDate) details.push(t('goals.projected', { month: monthYear(goal.projectedDate) }))
        else details.push(goal.monthlyPace > 0 ? t('goals.tooFar') : t('goals.notReachedAtPace'))
      }
    }
  } else if (goal.state !== 'REACHED' && goal.requiredMonthly !== null) {
    details.push(t('goals.requiredYear', { amount: money(goal.requiredMonthly, currency, 0) }))
  }

  return (
    <div>
      <div className="mb-1.5 flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
        <span className="flex flex-wrap items-center gap-2">
          <span className="font-medium">{goal.name}</span>
          {!compact && (
            <span className="text-xs text-muted">
              {goal.kind === 'YEARLY' ? t('goals.yearlyIn', { year: goal.year ?? '' }) : t('goals.kindBALANCE')}
            </span>
          )}
          {badge && <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${badge}`}>{t(`goals.state.${goal.state as 'REACHED' | 'ON_TRACK' | 'BEHIND'}`)}</span>}
        </span>
        <span className="tabular text-sm">
          <strong>{money(goal.current, currency, 0)}</strong>
          <span className="text-muted"> / {goal.target === null ? money(goal.targetAmount, goal.currency, 0) : money(goal.target, currency, 0)}</span>
        </span>
      </div>
      <div className="h-2 overflow-hidden rounded-full bg-surface-2" role="progressbar" aria-label={goal.name}
        aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(width)}>
        <div className={`h-full rounded-full ${BAR[goal.state]}`} style={{ width: `${width}%` }} />
      </div>
      <div className="mt-1.5 flex flex-wrap justify-between gap-x-4 gap-y-1 text-xs text-ink-2">
        <span>
          {goal.percent !== null && percent(goal.percent)}
          {goal.remaining !== null && goal.remaining > 0 && ` · ${t('goals.remaining', { amount: money(goal.remaining, currency, 0) })}`}
        </span>
        {!compact && details.length > 0 && <span className="flex flex-wrap gap-x-4 gap-y-1">{details.map((d) => <span key={d}>{d}</span>)}</span>}
      </div>
    </div>
  )
}
