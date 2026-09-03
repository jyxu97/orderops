import { useContext, useEffect, useRef } from 'react';
import { RealtimeContext } from './context';
import type { ConnectionStatus, EventHandler } from './connection';

function useRealtimeContext() {
  const context = useContext(RealtimeContext);
  if (!context) {
    throw new Error('useRealtime hooks require a <RealtimeProvider> ancestor');
  }
  return context;
}

export function useRealtimeStatus(): ConnectionStatus {
  return useRealtimeContext().status;
}

/**
 * Subscribes to `destination` for as long as the component is mounted.
 *
 * Pass `null` to subscribe to nothing — useful while the destination's ID is still loading,
 * and cheaper than conditionally calling the hook (which React forbids anyway).
 *
 * The handler is held in a ref so that an inline arrow function does not re-subscribe on every
 * render. Only a change of `destination` opens a new subscription.
 */
export function useOrderEvents(destination: string | null, handler: EventHandler): void {
  const { connection } = useRealtimeContext();
  const handlerRef = useRef(handler);

  useEffect(() => {
    handlerRef.current = handler;
  }, [handler]);

  useEffect(() => {
    if (!destination) {
      return;
    }
    return connection.subscribe(destination, (event) => handlerRef.current(event));
  }, [connection, destination]);
}
