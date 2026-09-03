import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { RealtimeConnection, type ConnectionStatus } from './connection';
import { RealtimeContext } from './context';

interface Props {
  children: ReactNode;
  /** Injected by tests to avoid opening a real socket. */
  connection?: RealtimeConnection | undefined;
}

/**
 * Owns the single shared STOMP connection and the resync-after-reconnect rule.
 *
 * The reconnect handler invalidates every query rather than a targeted subset. A client that
 * was disconnected has no way to know which events it missed, so anything cached could be
 * stale; invalidating broadly is the only version that cannot be subtly wrong. Reconnects are
 * rare, so the cost of over-refetching is paid almost never, while a missed invalidation would
 * leave the UI quietly showing an old status.
 */
export function RealtimeProvider({ children, connection: injected }: Props) {
  const queryClient = useQueryClient();
  const connection = useMemo(() => injected ?? new RealtimeConnection(), [injected]);
  const [status, setStatus] = useState<ConnectionStatus>(connection.getStatus());

  useEffect(() => {
    const unsubscribeStatus = connection.onStatusChange(setStatus);
    const unsubscribeReconnect = connection.onReconnect(() => {
      void queryClient.invalidateQueries();
    });

    connection.activate();

    return () => {
      unsubscribeStatus();
      unsubscribeReconnect();
      // Only tear the socket down if this provider created it; an injected one is the test's.
      if (!injected) {
        void connection.deactivate();
      }
    };
  }, [connection, injected, queryClient]);

  const value = useMemo(() => ({ connection, status }), [connection, status]);

  return <RealtimeContext.Provider value={value}>{children}</RealtimeContext.Provider>;
}
