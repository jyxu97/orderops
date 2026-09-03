import { queryString, request } from './client';
import type { FailedOrder, OpsOverview, OrderSummary, QueueHealth } from '../types';

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
