import { useEffect } from 'react'
import { useLocation, useNavigate } from 'react-router'
import type { CashEntry } from '../api/types'

/**
 * What a search result asks the page it opens to do, passed in the navigation state: open an
 * entry, a recurring entry or a goal for editing, or list the entries of a category, a tag or a
 * text.
 */
export type SearchHandoff =
  | { kind: 'entry'; entry: CashEntry }
  | { kind: 'entries'; filter: { q?: string; categoryId?: number; tagId?: number } }
  | { kind: 'recurring'; id: number }
  | { kind: 'goal'; id: number }

export const handoffState = (search: SearchHandoff) => ({ search })

/**
 * Hands the page the search result it was opened for, once. {@code accept} returns false while
 * the page cannot act on it yet (its data still loading) and is asked again on the next render;
 * once it returns true the state is cleared, so going back or reloading does not repeat it.
 */
export function useSearchHandoff(accept: (handoff: SearchHandoff) => boolean) {
  const location = useLocation()
  const navigate = useNavigate()
  const handoff = (location.state as { search?: SearchHandoff } | null)?.search
  useEffect(() => {
    if (handoff && accept(handoff)) {
      navigate({ pathname: location.pathname, search: location.search }, { replace: true, state: null })
    }
  })
}
