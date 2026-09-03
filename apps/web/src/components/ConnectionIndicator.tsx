import { useRealtimeStatus } from '../realtime/useRealtime';

const LABELS = {
  idle: 'Offline',
  connecting: 'Connecting…',
  connected: 'Live',
  reconnecting: 'Reconnecting…',
} as const;

/**
 * Shows the real-time connection state.
 *
 * Worth the pixels: when the socket is down the page is still correct but no longer updating
 * itself, and a user staring at a stale status deserves to know which of the two they are
 * looking at.
 */
export function ConnectionIndicator() {
  const status = useRealtimeStatus();
  return (
    <span className={`connection connection--${status}`} title="Real-time connection status">
      <span className="connection__dot" aria-hidden="true" />
      {LABELS[status]}
    </span>
  );
}
