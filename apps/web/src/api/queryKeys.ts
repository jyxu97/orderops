import type { OrderStatus } from '../types';

/**
 * Every React Query key in one place, so cache invalidation after a mutation or a WebSocket
 * event cannot drift from the keys the queries were registered under.
 *
 * Note the shape: list keys sit under `['orders', 'list']` and detail keys under
 * `['orders', 'detail']`. React Query matches by key prefix, so a flatter layout would make
 * "refresh the lists" also discard every cached order detail — including the authoritative
 * one a mutation just returned.
 */
export const queryKeys = {
  /** Everything order-related. Used by the reconnect resync, which deliberately casts wide. */
  orders: ['orders'] as const,

  /** Just the list views, so invalidating them leaves detail caches intact. */
  orderLists: ['orders', 'list'] as const,
  ordersByCustomer: (customerId: string) => ['orders', 'list', 'byCustomer', customerId] as const,
  ordersByStatus: (status: OrderStatus) => ['orders', 'list', 'byStatus', status] as const,

  order: (orderId: string) => ['orders', 'detail', orderId] as const,
  orderAudit: (orderId: string) => ['orders', 'audit', orderId] as const,

  inventory: ['inventory'] as const,

  ops: ['ops'] as const,
  opsOverview: ['ops', 'overview'] as const,
  opsFailures: ['ops', 'failures'] as const,
  opsQueueHealth: ['ops', 'queueHealth'] as const,
  opsDlqRedrive: ['ops', 'dlqRedrive'] as const,
};
