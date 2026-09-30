import { useState, type KeyboardEvent } from 'react'
import { X } from 'lucide-react'
import { useI18n } from '../i18n'

const MAX_TAGS = 10
export const MAX_TAG_LENGTH = 40

const clean = (text: string) => text.replace(/[\s,]+/g, ' ').trim().slice(0, MAX_TAG_LENGTH)

/**
 * Tags as removable chips plus a text box: Enter, comma or leaving the box adds what was typed;
 * Backspace in the empty box removes the last tag. Existing tags are suggested while typing.
 */
export function TagInput({ id, value, onChange, suggestions }: {
  id?: string
  value: string[]
  onChange: (tags: string[]) => void
  suggestions: string[]
}) {
  const { t } = useI18n()
  const [text, setText] = useState('')
  const taken = new Set(value.map((v) => v.toLowerCase()))
  const full = value.length >= MAX_TAGS

  function add(raw: string) {
    const name = clean(raw)
    setText('')
    if (!name || taken.has(name.toLowerCase()) || full) return
    // Reuse the spelling of an existing tag ("vacanze" -> "Vacanze")
    const existing = suggestions.find((s) => s.toLowerCase() === name.toLowerCase())
    onChange([...value, existing ?? name])
  }

  function onKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if ((e.key === 'Enter' || e.key === ',') && text.trim()) {
      e.preventDefault()
      add(text)
    } else if (e.key === 'Backspace' && !text && value.length > 0) {
      onChange(value.slice(0, -1))
    }
  }

  return (
    <div className="input flex min-h-10 flex-wrap items-center gap-1.5 py-1.5">
      {value.map((tag) => (
        <span key={tag} className="inline-flex items-center gap-1 rounded-full bg-accent-soft px-2 py-0.5 text-xs text-accent">
          {tag}
          <button type="button" className="rounded-full hover:text-ink" aria-label={t('tags.remove', { name: tag })}
            onClick={() => onChange(value.filter((v) => v !== tag))}>
            <X className="size-3" />
          </button>
        </span>
      ))}
      <input id={id} className="min-w-24 flex-1 bg-transparent text-sm outline-none" list={id ? `${id}-suggestions` : undefined}
        value={text} disabled={full} placeholder={value.length === 0 ? t('tags.placeholder') : ''} maxLength={MAX_TAG_LENGTH + 1}
        onChange={(e) => (e.target.value.endsWith(',') ? add(e.target.value) : setText(e.target.value))}
        onKeyDown={onKeyDown} onBlur={() => text.trim() && add(text)} />
      {id && (
        <datalist id={`${id}-suggestions`}>
          {suggestions.filter((s) => !taken.has(s.toLowerCase())).map((s) => <option key={s} value={s}>{s}</option>)}
        </datalist>
      )}
    </div>
  )
}

/** A tag shown on an entry; clickable when it filters the list. */
export function TagChip({ name, onClick }: { name: string; onClick?: () => void }) {
  const className = 'inline-flex items-center rounded-full bg-surface-2 px-2 py-0.5 text-xs text-ink-2'
  return onClick
    ? <button type="button" className={`${className} hover:bg-accent-soft hover:text-accent`} onClick={onClick}>{name}</button>
    : <span className={className}>{name}</span>
}
