import { Client, ReconnectionTimeMode, type IMessage } from '@stomp/stompjs';
import type { OrderStatusEvent } from '../types';

export type ConnectionStatus = 'idle' | 'connecting' | 'connected' | 'reconnecting';

export type EventHandler = (event: OrderStatusEvent) => void;
export type StatusListener = (status: ConnectionStatus) => void;
/** Fired after a reconnect, never after the first connect. */
export type ReconnectListener = () => void;

function defaultWebSocketUrl(): string {
  const configured = import.meta.env.VITE_WS_URL;
  if (configured) {
    return configured;
  }
  // Same origin as the app: the Vite dev server proxies /ws, and in a CloudFront deployment
  // the distribution routes /ws to the ALB.
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}/ws`;
}

/**
 * One STOMP connection per browser tab, shared by every component that needs live updates.
 *
 * A connection per component would mean a socket per mounted list row; the server holds each
 * subscription in the API instance's heap, so the connection count is the thing that decides
 * whether the API needs scaling out. Sharing one socket and multiplexing destinations over it
 * keeps that count at one.
 *
 * Two behaviours exist because STOMP alone does not give them:
 *
 * - **Re-subscription.** stompjs reconnects the socket but does not restore subscriptions, so
 *   the destinations are tracked here and re-sent on every connect.
 * - **Resync notification.** A client cannot know what it missed while disconnected, so
 *   reconnects are announced and the caller refetches from REST. Events are delivery hints;
 *   DynamoDB is the source of truth.
 */
export class RealtimeConnection {
  private readonly client: Client;
  private readonly handlers = new Map<string, Set<EventHandler>>();
  private readonly statusListeners = new Set<StatusListener>();
  private readonly reconnectListeners = new Set<ReconnectListener>();
  private readonly subscriptions = new Map<string, { unsubscribe: () => void }>();

  private status: ConnectionStatus = 'idle';
  private hasConnectedBefore = false;

  constructor(brokerURL: string = defaultWebSocketUrl()) {
    this.client = new Client({
      brokerURL,
      // Exponential backoff so a server restart is not met with a request storm from every
      // open tab. stompjs doubles from reconnectDelay up to maxReconnectDelay.
      reconnectDelay: 1_000,
      maxReconnectDelay: 30_000,
      reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
      // Heartbeats in both directions: without them a connection dropped by an intermediary
      // (an idle ALB, a sleeping laptop's NAT) looks alive until the next event is missed.
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      onConnect: () => this.onConnect(),
      onWebSocketClose: () => this.onDisconnect(),
      onStompError: (frame) => {
        console.error('STOMP error frame', frame.headers['message'], frame.body);
      },
    });
  }

  activate(): void {
    if (this.client.active) {
      return;
    }
    this.setStatus('connecting');
    this.client.activate();
  }

  async deactivate(): Promise<void> {
    this.subscriptions.forEach((subscription) => subscription.unsubscribe());
    this.subscriptions.clear();
    this.hasConnectedBefore = false;
    await this.client.deactivate();
    this.setStatus('idle');
  }

  /**
   * Registers `handler` for `destination` and returns an unsubscribe function.
   *
   * Several handlers may share one destination — an order row and a detail panel can both
   * watch the same order — so the STOMP subscription is created once and torn down only when
   * the last handler for it goes away.
   */
  subscribe(destination: string, handler: EventHandler): () => void {
    const existing = this.handlers.get(destination);
    if (existing) {
      existing.add(handler);
    } else {
      this.handlers.set(destination, new Set([handler]));
      this.openSubscription(destination);
    }

    return () => {
      const handlers = this.handlers.get(destination);
      if (!handlers) {
        return;
      }
      handlers.delete(handler);
      if (handlers.size === 0) {
        this.handlers.delete(destination);
        this.subscriptions.get(destination)?.unsubscribe();
        this.subscriptions.delete(destination);
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

  // ---------------------------------------------------------------------------

  private onConnect(): void {
    this.setStatus('connected');

    // stompjs restores the socket, not the subscriptions.
    this.subscriptions.clear();
    this.handlers.forEach((_handlers, destination) => this.openSubscription(destination));

    if (this.hasConnectedBefore) {
      this.reconnectListeners.forEach((listener) => listener());
    }
    this.hasConnectedBefore = true;
  }

  private onDisconnect(): void {
    if (this.client.active) {
      // The client is still trying, so this is a drop rather than a deliberate shutdown.
      this.setStatus('reconnecting');
    }
  }

  private openSubscription(destination: string): void {
    if (!this.client.connected) {
      // Deferred to onConnect, which subscribes every tracked destination.
      return;
    }
    const subscription = this.client.subscribe(destination, (message: IMessage) =>
      this.dispatch(destination, message),
    );
    this.subscriptions.set(destination, subscription);
  }

  private dispatch(destination: string, message: IMessage): void {
    let event: OrderStatusEvent;
    try {
      event = JSON.parse(message.body) as OrderStatusEvent;
    } catch {
      console.warn('Discarding unparseable order event', message.body);
      return;
    }
    // A throwing handler must not stop the others on the same destination.
    this.handlers.get(destination)?.forEach((handler) => {
      try {
        handler(event);
      } catch (error) {
        console.error('Order event handler failed', error);
      }
    });
  }

  private setStatus(status: ConnectionStatus): void {
    if (this.status === status) {
      return;
    }
    this.status = status;
    this.statusListeners.forEach((listener) => listener(status));
  }
}
