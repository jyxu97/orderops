import type {
  ConnectionStatus,
  EventHandler,
  ReconnectListener,
  RealtimeConnectionLike,
  StatusListener,
} from '../realtime/connection';
import type { OrderStatusEvent } from '../types';

/**
 * A driveable stand-in for the STOMP connection.
 *
 * Tests need to push an event at a specific destination and to simulate a reconnect, neither of
 * which is reachable through a real socket under jsdom.
 */
export class FakeRealtimeConnection implements RealtimeConnectionLike {
  private readonly handlers = new Map<string, Set<EventHandler>>();
  private readonly statusListeners = new Set<StatusListener>();
  private readonly reconnectListeners = new Set<ReconnectListener>();

  activated = false;
  private status: ConnectionStatus = 'connected';

  activate(): void {
    this.activated = true;
  }

  async deactivate(): Promise<void> {
    this.activated = false;
  }

  subscribe(destination: string, handler: EventHandler): () => void {
    const handlers = this.handlers.get(destination) ?? new Set();
    handlers.add(handler);
    this.handlers.set(destination, handlers);

    return () => {
      handlers.delete(handler);
      if (handlers.size === 0) {
        this.handlers.delete(destination);
      }
    };
  }

  onStatusChange(listener: StatusListener): () => void {
    this.statusListeners.add(listener);
    listener(this.status);
    return () => this.statusListeners.delete(listener);
  }

  onReconnect(listener: ReconnectListener): () => void {
    this.reconnectListeners.add(listener);
    return () => this.reconnectListeners.delete(listener);
  }

  getStatus(): ConnectionStatus {
    return this.status;
  }

  // ── Test controls ─────────────────────────────────────────────────────────

  /** Destinations with at least one live subscriber. */
  subscribedDestinations(): string[] {
    return [...this.handlers.keys()];
  }

  /** Delivers an event to whoever is subscribed to `destination`. */
  emit(destination: string, event: OrderStatusEvent): void {
    this.handlers.get(destination)?.forEach((handler) => handler(event));
  }

  setStatus(status: ConnectionStatus): void {
    this.status = status;
    this.statusListeners.forEach((listener) => listener(status));
  }

  /** Fires the reconnect notification the provider turns into a resync. */
  simulateReconnect(): void {
    this.reconnectListeners.forEach((listener) => listener());
  }
}
