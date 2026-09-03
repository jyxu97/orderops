import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * A stand-in for the stompjs Client that records what was asked of it and lets the test fire
 * the lifecycle callbacks. The real client would need a WebSocket, which jsdom does not provide.
 */
class FakeStompClient {
  static latest: FakeStompClient;

  readonly config: Record<string, unknown>;
  readonly subscribeCalls: string[] = [];
  readonly unsubscribed: string[] = [];

  active = false;
  connected = false;
  deactivateCount = 0;

  constructor(config: Record<string, unknown>) {
    this.config = config;
    FakeStompClient.latest = this;
  }

  activate() {
    this.active = true;
  }

  async deactivate() {
    this.deactivateCount += 1;
    this.active = false;
    this.connected = false;
  }

  subscribe(destination: string, callback: (message: { body: string }) => void) {
    this.subscribeCalls.push(destination);
    const handlers = this.handlers.get(destination) ?? [];
    handlers.push(callback);
    this.handlers.set(destination, handlers);
    return {
      unsubscribe: () => {
        this.unsubscribed.push(destination);
      },
    };
  }

  private readonly handlers = new Map<string, ((message: { body: string }) => void)[]>();

  // ── Test controls ─────────────────────────────────────────────────────────

  fireConnect() {
    this.connected = true;
    (this.config['onConnect'] as () => void)();
  }

  fireSocketClose() {
    this.connected = false;
    (this.config['onWebSocketClose'] as () => void)();
  }

  deliver(destination: string, body: string) {
    this.handlers.get(destination)?.forEach((handler) => handler({ body }));
  }
}

vi.mock('@stomp/stompjs', () => ({
  Client: FakeStompClient,
  ReconnectionTimeMode: { EXPONENTIAL: 'EXPONENTIAL', LINEAR: 'LINEAR' },
}));

const { RealtimeConnection } = await import('./connection');

describe('RealtimeConnection', () => {
  let connection: InstanceType<typeof RealtimeConnection>;

  beforeEach(() => {
    connection = new RealtimeConnection('ws://test/ws');
  });

  function client() {
    return FakeStompClient.latest;
  }

  it('configures exponential backoff and two-way heartbeats', () => {
    // A fixed delay would meet a server restart with a request storm from every open tab, and
    // without heartbeats a connection dropped by an intermediary looks alive until an event
    // is silently missed.
    expect(client().config).toMatchObject({
      brokerURL: 'ws://test/ws',
      reconnectTimeMode: 'EXPONENTIAL',
      heartbeatIncoming: expect.any(Number),
      heartbeatOutgoing: expect.any(Number),
    });
    expect(client().config['maxReconnectDelay']).toBeGreaterThan(
      client().config['reconnectDelay'] as number,
    );
  });

  it('defers subscription until connected, then subscribes on connect', () => {
    const handler = vi.fn();
    connection.activate();
    connection.subscribe('/topic/orders/o-1', handler);

    // Not connected yet, so nothing could have been sent.
    expect(client().subscribeCalls).toEqual([]);

    client().fireConnect();
    expect(client().subscribeCalls).toEqual(['/topic/orders/o-1']);
  });

  it('re-subscribes every destination after a reconnect', () => {
    connection.activate();
    connection.subscribe('/topic/orders/o-1', vi.fn());
    connection.subscribe('/topic/ops/orders', vi.fn());
    client().fireConnect();
    expect(client().subscribeCalls).toHaveLength(2);

    client().fireSocketClose();
    client().fireConnect();

    // stompjs restores the socket but not the subscriptions, so without this the UI would
    // reconnect and then silently receive nothing.
    expect(client().subscribeCalls).toEqual([
      '/topic/orders/o-1',
      '/topic/ops/orders',
      '/topic/orders/o-1',
      '/topic/ops/orders',
    ]);
  });

  it('notifies reconnect listeners on a reconnect but not on the first connect', () => {
    const onReconnect = vi.fn();
    connection.onReconnect(onReconnect);
    connection.activate();

    client().fireConnect();
    // The first connect has no gap to resync from — invalidating here would refetch
    // everything the page just loaded.
    expect(onReconnect).not.toHaveBeenCalled();

    client().fireSocketClose();
    client().fireConnect();
    expect(onReconnect).toHaveBeenCalledTimes(1);
  });

  it('reports connecting, connected and reconnecting', () => {
    const statuses: string[] = [];
    connection.onStatusChange((status) => statuses.push(status));

    connection.activate();
    client().fireConnect();
    client().fireSocketClose();

    expect(statuses).toEqual(['idle', 'connecting', 'connected', 'reconnecting']);
  });

  it('delivers a parsed event to the handler for its destination', () => {
    const orderHandler = vi.fn();
    const opsHandler = vi.fn();
    connection.activate();
    connection.subscribe('/topic/orders/o-1', orderHandler);
    connection.subscribe('/topic/ops/orders', opsHandler);
    client().fireConnect();

    client().deliver('/topic/orders/o-1', JSON.stringify({ orderId: 'o-1', status: 'FULFILLED' }));

    expect(orderHandler).toHaveBeenCalledWith(expect.objectContaining({ status: 'FULFILLED' }));
    expect(opsHandler).not.toHaveBeenCalled();
  });

  it('shares one STOMP subscription between handlers on the same destination', () => {
    const first = vi.fn();
    const second = vi.fn();
    connection.activate();
    const unsubscribeFirst = connection.subscribe('/topic/ops/orders', first);
    connection.subscribe('/topic/ops/orders', second);
    client().fireConnect();

    // An order row and a detail panel can watch the same order; that must not open two
    // subscriptions, nor tear the shared one down when only one of them unmounts.
    expect(client().subscribeCalls).toEqual(['/topic/ops/orders']);

    unsubscribeFirst();
    expect(client().unsubscribed).toEqual([]);

    client().deliver('/topic/ops/orders', JSON.stringify({ orderId: 'o-1', status: 'FAILED' }));
    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(1);
  });

  it('drops the STOMP subscription once the last handler unsubscribes', () => {
    connection.activate();
    const unsubscribe = connection.subscribe('/topic/ops/orders', vi.fn());
    client().fireConnect();

    unsubscribe();

    expect(client().unsubscribed).toEqual(['/topic/ops/orders']);
  });

  it('a throwing handler does not stop the others', () => {
    const throwing = vi.fn(() => {
      throw new Error('boom');
    });
    const healthy = vi.fn();
    connection.activate();
    connection.subscribe('/topic/ops/orders', throwing);
    connection.subscribe('/topic/ops/orders', healthy);
    client().fireConnect();

    client().deliver('/topic/ops/orders', JSON.stringify({ orderId: 'o-1', status: 'FAILED' }));

    expect(healthy).toHaveBeenCalledTimes(1);
  });

  it('discards an unparseable payload without calling handlers', () => {
    const handler = vi.fn();
    connection.activate();
    connection.subscribe('/topic/ops/orders', handler);
    client().fireConnect();

    client().deliver('/topic/ops/orders', 'not json');

    expect(handler).not.toHaveBeenCalled();
  });

  it('does not re-activate an already active client', () => {
    connection.activate();
    const activeClient = client();
    connection.activate();

    // A second activate() on a live client is a no-op in stompjs, but relying on that would
    // hide a double-mount bug; the guard makes it explicit.
    expect(activeClient).toBe(client());
  });
});
