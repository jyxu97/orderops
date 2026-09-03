import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, type RenderResult } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { ReactElement, ReactNode } from 'react';
import { RealtimeProvider } from '../realtime/RealtimeContext';
import { FakeRealtimeConnection } from './FakeRealtimeConnection';
import type { InventoryItem, Order, OrderStatus, OrderStatusEvent } from '../types';

interface RenderOptions {
  /** Initial URL. Use with `path` for routes that read params. */
  route?: string;
  /** Route pattern, e.g. `/orders/:orderId`. Defaults to matching anything. */
  path?: string;
  connection?: FakeRealtimeConnection;
}

export interface RenderWithProvidersResult extends RenderResult {
  connection: FakeRealtimeConnection;
  queryClient: QueryClient;
}

/**
 * Renders a page with the providers it expects.
 *
 * Retries are off and `gcTime` is zero: a retry would make a test asserting an error state wait
 * out backoff, and a shared cache would leak state between tests.
 */
export function renderWithProviders(
  ui: ReactElement,
  { route = '/', path = '*', connection = new FakeRealtimeConnection() }: RenderOptions = {},
): RenderWithProvidersResult {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0 },
      mutations: { retry: false },
    },
  });

  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <RealtimeProvider connection={connection}>
        <MemoryRouter initialEntries={[route]}>
          <Routes>
            <Route path={path} element={children} />
          </Routes>
        </MemoryRouter>
      </RealtimeProvider>
    </QueryClientProvider>
  );

  const result = render(ui, { wrapper });
  return { ...result, connection, queryClient };
}

// ── Fixtures ────────────────────────────────────────────────────────────────

export function anInventoryItem(overrides: Partial<InventoryItem> = {}): InventoryItem {
  return {
    itemId: 'widget-a',
    itemName: 'Widget A',
    unitPrice: 19.99,
    totalQuantity: 100,
    availableQuantity: 100,
    reservedQuantity: 0,
    version: 0,
    ...overrides,
  };
}

export function anOrder(overrides: Partial<Order> = {}): Order {
  return {
    orderId: 'order-1234-5678',
    customerId: 'customer-1',
    items: [{ itemId: 'widget-a', quantity: 2, unitPrice: 19.99, lineTotal: 39.98 }],
    status: 'INVENTORY_RESERVED',
    totalAmount: 39.98,
    cancellable: true,
    version: 1,
    createdAt: '2026-09-03T08:00:00.000Z',
    updatedAt: '2026-09-03T08:00:00.000Z',
    ...overrides,
  };
}

export function anEvent(status: OrderStatus, overrides: Partial<OrderStatusEvent> = {}): OrderStatusEvent {
  return {
    type: 'ORDER_STATUS_CHANGED',
    orderId: 'order-1234-5678',
    customerId: 'customer-1',
    status,
    occurredAt: '2026-09-03T08:00:05.000Z',
    committedAtEpochMilli: Date.now(),
    ...overrides,
  };
}
