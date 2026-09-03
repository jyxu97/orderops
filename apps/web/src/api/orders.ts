import { queryString, request } from './client';
import type {
  CreateOrderRequest,
  CreateOrderResponse,
  Order,
  OrderAuditEntry,
  OrderStatus,
  OrderSummary,
  Page,
} from '../types';

export function createOrder(
  body: CreateOrderRequest,
  idempotencyKey: string,
): Promise<CreateOrderResponse> {
  return request('/api/v1/orders', { method: 'POST', body, idempotencyKey });
}

export function getOrder(orderId: string): Promise<Order> {
  return request(`/api/v1/orders/${encodeURIComponent(orderId)}`);
}

export function getOrderAudit(orderId: string): Promise<OrderAuditEntry[]> {
  return request(`/api/v1/orders/${encodeURIComponent(orderId)}/audit`);
}

export function cancelOrder(orderId: string): Promise<Order> {
  return request(`/api/v1/orders/${encodeURIComponent(orderId)}/cancel`, { method: 'POST' });
}

/** The backend requires exactly one filter, so the two list calls stay separate functions. */
export function listOrdersByCustomer(
  customerId: string,
  options: { limit?: number; cursor?: string } = {},
): Promise<Page<OrderSummary>> {
  return request(`/api/v1/orders${queryString({ customerId, ...options })}`);
}

export function listOrdersByStatus(
  status: OrderStatus,
  options: { limit?: number; cursor?: string } = {},
): Promise<Page<OrderSummary>> {
  return request(`/api/v1/orders${queryString({ status, ...options })}`);
}
