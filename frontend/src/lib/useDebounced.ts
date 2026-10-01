import { useEffect, useState } from 'react'

/** The value once it has not changed for `ms`: following the typing without a request per key. */
export function useDebounced<T>(value: T, ms: number) {
  const [settled, setSettled] = useState(value)
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), ms)
    return () => clearTimeout(timer)
  }, [value, ms])
  return settled
}
