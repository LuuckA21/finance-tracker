/** Lower case, without accents, punctuation and repeated spaces: as the server compares descriptions. */
export function normalizeText(text: string) {
  return text.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim()
}

/** Whether a description contains a rule's text, as the server decides it. */
export function matchesPattern(description: string | null, pattern: string) {
  const text = normalizeText(pattern)
  return text.length > 0 && normalizeText(description ?? '').includes(text)
}

/**
 * The text proposed for a new rule from a description: its words without digits (dates, card and
 * reference numbers change from one bank line to the next), at most four.
 */
export function patternFor(description: string | null) {
  return normalizeText(description ?? '').split(' ').filter((w) => w && !/\d/.test(w)).slice(0, 4).join(' ')
}
