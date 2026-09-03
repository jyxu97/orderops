import { useCallback, useEffect, useRef } from 'react';

/**
 * Collapses a burst of calls into one, fired on the trailing edge of `delayMs`.
 *
 * The operations dashboard subscribes to `/topic/ops/orders`, which carries an event for
 * every state change of every order — four or five per order as it moves through fulfillment.
 * Refetching per event would turn a checkout burst into a refetch storm against the same API
 * the burst is already loading, and each refetch would be stale before it landed anyway.
 * Coalescing means a spike of a thousand events costs one extra request per window.
 *
 * Trailing rather than leading edge: the point is to read the state *after* the burst settles.
 */
export function useCoalescedCallback(callback: () => void, delayMs: number): () => void {
  const callbackRef = useRef(callback);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    callbackRef.current = callback;
  }, [callback]);

  // Clear on unmount so a pending fire cannot touch an unmounted component's queries.
  useEffect(
    () => () => {
      if (timerRef.current !== null) {
        clearTimeout(timerRef.current);
      }
    },
    [],
  );

  return useCallback(() => {
    if (timerRef.current !== null) {
      return;
    }
    timerRef.current = setTimeout(() => {
      timerRef.current = null;
      callbackRef.current();
    }, delayMs);
  }, [delayMs]);
}
