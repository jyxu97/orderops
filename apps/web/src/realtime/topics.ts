/**
 * Mirrors `com.orderops.realtime.RealtimeTopics`. The backend allowlists SUBSCRIBE frames, so
 * a destination built anywhere but here will be silently dropped by the server — building them
 * in one place keeps the two definitions from drifting apart.
 */
export const topics = {
  order: (orderId: string) => `/topic/orders/${orderId}`,
  customerOrders: (customerId: string) => `/topic/customers/${customerId}/orders`,
  opsOrders: '/topic/ops/orders',
} as const;
