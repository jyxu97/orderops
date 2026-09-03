import type { OrderStatus } from '../types';

/**
 * Every React Query key in one place, so cache invalidation after a mutation or a WebSocket
 * event cannot drift from the keys the queries were registered under.
 */
export const queryKeys = {
  orders: ['orders'] as const,
  order: (orderId: string) => ['orders', 'detail', orderId] as const,
  orderAudit: (orderId: string) => ['orders', 'audit', orderId] as const,
  ordersByCustomer: (customerId: string) => ['orders', 'byCustomer', customerId] as const,
  ordersByStatus: (status: OrderStatus) => ['orders', 'byStatus', status] as const,

  inventory: ['inventory'] as const,

  ops: ['ops'] as const,
  opsOverview: ['ops', 'overview'] as const,
  opsFailures: ['ops', 'failures'] as const,
  opsQueueHealth: ['ops', 'queueHealth'] as const,
  opsDlqRedrive: ['ops', 'dlqRedrive'] as const,
};
