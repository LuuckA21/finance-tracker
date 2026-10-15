import { useEffect, useId, useMemo, useRef, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { ArrowLeftRight, Flag, Folder, List, Repeat, Search, Tag, Wallet, X, type LucideIcon } from 'lucide-react'
import { SEARCH_MIN_LENGTH, useCategories, useSearch } from '../api/hooks'
import type { SearchResult } from '../api/types'
import { useI18n, type MessageKey } from '../i18n'
import { date, money } from '../lib/format'
import { handoffState, type SearchHandoff } from '../lib/search'
import { amountStyle } from './TransferFields'

/** Waits for a pause in typing before searching. */
const DEBOUNCE_MS = 200

interface Option {
  key: string
  group: MessageKey
  icon: LucideIcon
  label: ReactNode
  detail?: ReactNode
  go: () => void
}

/**
 * The search box: one text across entries, categories, tags, positions, recurring entries and
 * goals. Arrow keys move through the results, Enter opens one, Escape closes.
 */
export function SearchDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const ref = useRef<HTMLDialogElement>(null)
  const { t } = useI18n()
  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    if (open && !dialog.open) dialog.showModal()
    if (!open && dialog.open) dialog.close()
  }, [open])

  return (
    <dialog
      ref={ref}
      aria-label={t('search.open')}
      onClose={onClose}
      onCancel={(e) => {
        e.preventDefault()
        onClose()
      }}
      className="mx-auto mt-[10vh] w-[calc(100%-2rem)] max-w-xl rounded-2xl border border-line bg-surface p-0 text-ink shadow-xl backdrop:bg-black/40"
    >
      {open && <SearchPanel onClose={onClose} />}
    </dialog>
  )
}

function SearchPanel({ onClose }: { onClose: () => void }) {
  const { t } = useI18n()
  const navigate = useNavigate()
  const [input, setInput] = useState('')
  const [text, setText] = useState('')
  // The highlighted result, by key: a new list highlights its first one
  const [activeKey, setActiveKey] = useState<string | null>(null)
  const listId = useId()
  const categories = useCategories().data
  const search = useSearch(text)

  useEffect(() => {
    const timer = setTimeout(() => setText(input.trim().replace(/\s+/g, ' ')), DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [input])

  const options = useMemo(() => {
    const go = (to: string, handoff?: SearchHandoff) => () => {
      onClose()
      navigate(to, handoff ? { state: handoffState(handoff) } : undefined)
    }
    const categoryName = (id: number | null) => categories?.find((c) => c.id === id)?.name
    return text.length < SEARCH_MIN_LENGTH || !search.data ? [] : buildOptions(search.data, text, t, go, categoryName)
  }, [search.data, text, t, navigate, onClose, categories])

  const active = Math.max(0, options.findIndex((o) => o.key === activeKey))
  const setActive = (index: number) => setActiveKey(options[index]?.key ?? null)

  function onKeyDown(e: React.KeyboardEvent) {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      if (options.length === 0) return
      setActive((active + (e.key === 'ArrowDown' ? 1 : options.length - 1)) % options.length)
    } else if (e.key === 'Enter' && options[active]) {
      e.preventDefault()
      options[active].go()
    } else if (e.key === 'Escape') {
      e.preventDefault()
      onClose()
    }
  }

  useEffect(() => {
    document.getElementById(`${listId}-${active}`)?.scrollIntoView({ block: 'nearest' })
  }, [active, listId])

  const searching = text.length >= SEARCH_MIN_LENGTH
  let status: string | null = null
  if (input.trim().length < SEARCH_MIN_LENGTH) status = t('search.hint')
  else if (search.isError) status = t('search.error')
  else if (!searching || (search.isFetching && options.length === 0)) status = t('search.loading')
  else if (options.length === 0) status = t('search.none', { text })

  return (
    <div className="flex max-h-[75vh] flex-col">
      <div className="flex items-center gap-2 border-b border-line px-4 py-3">
        <Search className="size-4 shrink-0 text-muted" aria-hidden />
        <input
          autoFocus
          // Plain text: a search field adds its own clear button next to the close one
          type="text"
          enterKeyHint="search"
          autoComplete="off"
          spellCheck={false}
          role="combobox"
          aria-label={t('search.open')}
          aria-expanded={options.length > 0}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={options[active] ? `${listId}-${active}` : undefined}
          className="w-full bg-transparent text-base outline-none placeholder:text-muted"
          placeholder={t('search.placeholder')}
          maxLength={100}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={onKeyDown}
        />
        <button type="button" onClick={onClose} className="rounded-md p-1 text-muted hover:bg-surface-2" aria-label={t('common.close')}>
          <X className="size-4" />
        </button>
      </div>
      {status && <p role="status" className="px-4 py-6 text-center text-sm text-muted">{status}</p>}
      <div id={listId} role="listbox" aria-label={t('search.results')} className="overflow-y-auto py-1">
        {groups(options).map(({ group, items }) => (
          <div key={group} role="group" aria-labelledby={`${listId}-${group}`}>
            <p id={`${listId}-${group}`} className="px-4 pt-3 pb-1 text-xs font-medium tracking-wide text-muted uppercase">
              {t(group)}
            </p>
            {items.map(({ option, index }) => (
              <div
                key={option.key}
                id={`${listId}-${index}`}
                role="option"
                tabIndex={-1}
                aria-selected={index === active}
                onMouseMove={() => setActive(index)}
                onClick={option.go}
                onKeyDown={(e) => { if (e.key === 'Enter') option.go() }}
                className={`flex cursor-pointer items-center gap-3 px-4 py-2 text-sm ${index === active ? 'bg-accent-soft' : ''}`}
              >
                <option.icon className="size-4 shrink-0 text-muted" aria-hidden />
                <span className="min-w-0 flex-1 truncate">{option.label}</span>
                {option.detail && <span className="shrink-0 text-xs text-muted">{option.detail}</span>}
              </div>
            ))}
          </div>
        ))}
      </div>
      <p className="hidden border-t border-line px-4 py-2 text-xs text-muted sm:block">{t('search.keys')}</p>
    </div>
  )
}

/** The options in their groups, each with its place in the whole list (for the arrow keys). */
function groups(options: Option[]): { group: MessageKey; items: { option: Option; index: number }[] }[] {
  const result: { group: MessageKey; items: { option: Option; index: number }[] }[] = []
  options.forEach((option, index) => {
    const last = result[result.length - 1]
    if (last?.group === option.group) last.items.push({ option, index })
    else result.push({ group: option.group, items: [{ option, index }] })
  })
  return result
}

function buildOptions(
  result: SearchResult,
  text: string,
  t: (key: MessageKey, vars?: Record<string, string | number>) => string,
  go: (to: string, search?: SearchHandoff) => () => void,
  categoryName: (id: number | null) => string | undefined,
): Option[] {
  const options: Option[] = []
  for (const entry of result.entries) {
    const style = amountStyle(entry.kind)
    options.push({
      key: `entry-${entry.id}`,
      group: 'search.entries',
      icon: ArrowLeftRight,
      label: entry.description || categoryName(entry.categoryId) || t('transfer.label'),
      detail: <>{date(entry.date)} · <span className={style.className}>{style.sign}{money(entry.amount, entry.currency)}</span></>,
      go: go('/movimenti', { kind: 'entry', entry }),
    })
  }
  if (result.entriesTotal > result.entries.length) {
    options.push({
      key: 'entries-all',
      group: 'search.entries',
      icon: List,
      label: t('search.allEntries', { count: result.entriesTotal, text }),
      go: go('/movimenti', { kind: 'entries', filter: { q: text } }),
    })
  }
  for (const category of result.categories) {
    options.push({
      key: `category-${category.id}`,
      group: 'search.categories',
      icon: Folder,
      label: (
        <span className="flex items-center gap-2">
          <span className="size-2.5 shrink-0 rounded-full" style={{ backgroundColor: category.color }} aria-hidden />
          <span className="truncate">{category.parentName ? `${category.parentName} › ${category.name}` : category.name}</span>
        </span>
      ),
      detail: t(category.kind === 'INCOME' ? 'entryForm.income' : 'entryForm.expense'),
      go: go('/movimenti', { kind: 'entries', filter: { categoryId: category.id } }),
    })
  }
  for (const tag of result.tags) {
    options.push({
      key: `tag-${tag.id}`,
      group: 'search.tags',
      icon: Tag,
      label: tag.name,
      go: go('/movimenti', { kind: 'entries', filter: { tagId: tag.id } }),
    })
  }
  for (const position of result.positions) {
    options.push({
      key: `position-${position.id}`,
      group: 'search.positions',
      icon: Wallet,
      label: position.symbol ? `${position.name} (${position.symbol})` : position.name,
      detail: position.archived ? t('search.archived') : t(`assetClass.${position.assetClass}`),
      go: go(`/posizioni/${position.id}`),
    })
  }
  for (const rule of result.recurring) {
    const style = amountStyle(rule.kind)
    options.push({
      key: `recurring-${rule.id}`,
      group: 'search.recurring',
      icon: Repeat,
      label: rule.description,
      detail: rule.active
        ? <>{t(`recurring.freq.${rule.frequency}`)} · {style.sign}{money(rule.amount, rule.currency)}</>
        : t('recurring.paused'),
      go: go('/ricorrenti', { kind: 'recurring', id: rule.id }),
    })
  }
  for (const goal of result.goals) {
    options.push({
      key: `goal-${goal.id}`,
      group: 'search.goals',
      icon: Flag,
      label: goal.name,
      go: go('/obiettivi', { kind: 'goal', id: goal.id }),
    })
  }
  return options
}
