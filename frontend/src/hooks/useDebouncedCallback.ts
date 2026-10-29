import { useEffect, useRef } from 'react'

/**
 * Returns a function that calls `callback` only after it hasn't been called
 * for `delayMs` (e.g. update the URL 300 ms after the user stops typing).
 * Always calls the latest `callback`, and cancels a pending call on unmount.
 */
export function useDebouncedCallback<A extends unknown[]>(
  callback: (...args: A) => void,
  delayMs = 300,
): (...args: A) => void {
  const latest = useRef(callback)
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)

  useEffect(() => {
    latest.current = callback
  })
  useEffect(() => () => clearTimeout(timer.current), [])

  return (...args: A) => {
    clearTimeout(timer.current)
    timer.current = setTimeout(() => latest.current(...args), delayMs)
  }
}
