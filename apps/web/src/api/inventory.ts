import { queryString, request } from './client';
import type { InventoryItem } from '../types';

export function listInventory(limit = 50): Promise<InventoryItem[]> {
  return request(`/api/v1/inventory${queryString({ limit })}`);
}

export function getInventoryItem(itemId: string): Promise<InventoryItem> {
  return request(`/api/v1/inventory/${encodeURIComponent(itemId)}`);
}
