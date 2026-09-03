import { queryString, request } from './client';
import type { DlqRedrive, FailedOrder, OpsOverview, OrderSummary, QueueHealth } from '../types';

export function getOpsOverview(recentLimit = 20): Promise<OpsOverview> {
  return request(`/api/v1/ops/overview${queryString({ recentLimit })}`);
}

export function getRecentOrders(limit = 25): Promise<OrderSummary[]> {
  return request(`/api/v1/ops/orders${queryString({ limit })}`);
}

export function getFailures(limit = 25): Promise<FailedOrder[]> {
  return request(`/api/v1/ops/failures${queryString({ limit })}`);
}

export function getQueueHealth(): Promise<QueueHealth> {
  return request('/api/v1/ops/queue-health');
}

/** Starts moving everything in the DLQ back to the fulfillment queue. 409 if one is running. */
export function startDlqRedrive(): Promise<DlqRedrive> {
  return request('/api/v1/ops/dlq/redrive', { method: 'POST' });
}

export function getDlqRedriveStatus(): Promise<DlqRedrive> {
  return request('/api/v1/ops/dlq/redrive');
}
