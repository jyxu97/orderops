import { createContext } from 'react';
import type { RealtimeConnectionLike, ConnectionStatus } from './connection';

export interface RealtimeContextValue {
  connection: RealtimeConnectionLike;
  status: ConnectionStatus;
}

/**
 * Kept in its own module so `RealtimeContext.tsx` exports only a component. Mixing a
 * non-component export into a component file breaks React Fast Refresh for that file.
 */
export const RealtimeContext = createContext<RealtimeContextValue | null>(null);
