import { screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { OperationsPage } from './OperationsPage';
import { anEvent, renderWithProviders } from '../../test/testUtils';
import { FakeRealtimeConnection } from '../../test/FakeRealtimeConnection';
import type { OpsOverview, OrderStatus } from '../../types';

vi.mock('../../api/operations');

const { getOpsOverview } = await import('../../api/operations');

const ZERO_COUNTS = {
  CREATED: 0,
  INVENTORY_RESERVED: 0,
  PAYMENT_PROCESSING: 0,
  PAYMENT_SUCCEEDED: 0,
  SHIPMENT_PROCESSING: 0,
  FULFILLED: 0,
  FAILED: 0,
  NEEDS_MANUAL_REVIEW: 0,
  CANCELLED: 0,
} satisfies Record<OrderStatus, number>;

function anOverview(overrides: Partial<OpsOverview> = {}): OpsOverview {
  return {
    statusCounts: { ...ZERO_COUNTS },
    countsCapped: false,
    recentOrders: [],
    queueHealth: {
      available: true,
      healthy: true,
      warnings: [],
      backlogThreshold: 100,
      queue: {
        queueName: 'order-fulfillment-queue',
        visibleMessages: 0,
        inFlightMessages: 0,
        delayedMessages: 0,
      },
      deadLetterQueue: {
        queueName: 'order-fulfillment-dlq',
        visibleMessages: 0,
        inFlightMessages: 0,
        delayedMessages: 0,
      },
    },
    generatedAt: new Date().toISOString(),
    ...overrides,
  };
}

describe('OperationsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(getOpsOverview).mockResolvedValue(anOverview());
  });

  it('aggregates the in-flight statuses into one tile', async () => {
    vi.mocked(getOpsOverview).mockResolvedValue(
      anOverview({
        statusCounts: {
          ...ZERO_COUNTS,
          INVENTORY_RESERVED: 2,
          PAYMENT_PROCESSING: 3,
          PAYMENT_SUCCEEDED: 1,
          SHIPMENT_PROCESSING: 4,
        },
      }),
    );
    renderWithProviders(<OperationsPage />);

    const tile = (await screen.findByText('In fulfillment')).closest('.stat');
    expect(tile).toHaveTextContent('10');
  });

  it('aggregates failed and manual-review into needs attention', async () => {
    vi.mocked(getOpsOverview).mockResolvedValue(
      anOverview({ statusCounts: { ...ZERO_COUNTS, FAILED: 2, NEEDS_MANUAL_REVIEW: 5 } }),
    );
    renderWithProviders(<OperationsPage />);

    const tile = (await screen.findByText('Needs attention')).closest('.stat');
    expect(tile).toHaveTextContent('7');
  });

  it('lists every status so the breakdown has a stable set of rows', async () => {
    renderWithProviders(<OperationsPage />);
    // Scoped to the panel: "Cancelled" is also a KPI tile label.
    const panel = (await screen.findByText('Orders by status')).closest('.panel');
    expect(panel).not.toBeNull();

    // Nine statuses are past the point where more colour helps, so this is a table — and it
    // must not drop rows just because their count is zero.
    const rows = within(panel as HTMLElement);
    for (const label of ['Created', 'Inventory reserved', 'Fulfilled', 'Needs review', 'Cancelled']) {
      expect(rows.getByText(label)).toBeInTheDocument();
    }
  });

  it('links a non-zero status to that filter on the order list', async () => {
    vi.mocked(getOpsOverview).mockResolvedValue(
      anOverview({ statusCounts: { ...ZERO_COUNTS, FULFILLED: 12 } }),
    );
    renderWithProviders(<OperationsPage />);

    const link = await screen.findByRole('link', { name: '12' });
    expect(link).toHaveAttribute('href', '/orders?status=FULFILLED');
  });

  it('does not link a status with no orders', async () => {
    renderWithProviders(<OperationsPage />);
    await screen.findByText('Orders by status');

    expect(screen.queryByRole('link', { name: '0' })).not.toBeInTheDocument();
  });

  it('warns that counts are lower bounds when the count query hit its cap', async () => {
    vi.mocked(getOpsOverview).mockResolvedValue(anOverview({ countsCapped: true }));
    renderWithProviders(<OperationsPage />);

    expect(await screen.findByText('Counts are lower bounds')).toBeInTheDocument();
  });

  it('does not warn when the counts are exact', async () => {
    renderWithProviders(<OperationsPage />);
    await screen.findByText('Orders by status');

    expect(screen.queryByText('Counts are lower bounds')).not.toBeInTheDocument();
  });

  it('renders queue depth against the threshold the server reported', async () => {
    vi.mocked(getOpsOverview).mockResolvedValue(
      anOverview({
        queueHealth: {
          ...anOverview().queueHealth,
          healthy: false,
          warnings: ['Queue backlog is 500 message(s), above the threshold of 100'],
          queue: {
            queueName: 'order-fulfillment-queue',
            visibleMessages: 500,
            inFlightMessages: 4,
            delayedMessages: 0,
          },
        },
      }),
    );
    renderWithProviders(<OperationsPage />);

    expect(await screen.findByText('Attention needed')).toBeInTheDocument();
    expect(screen.getByText(/threshold 100/)).toBeInTheDocument();
    expect(screen.getByText(/above the threshold of 100/)).toBeInTheDocument();
  });

  it('renders unknown, not healthy, when SQS could not be read', async () => {
    vi.mocked(getOpsOverview).mockResolvedValue(
      anOverview({
        queueHealth: { available: false, unavailableReason: 'queue is gone' },
      }),
    );
    renderWithProviders(<OperationsPage />);

    // An unread metric must never render as fine.
    expect(await screen.findByText(/Queue depth is unavailable/)).toBeInTheDocument();
    expect(screen.queryByText('Healthy')).not.toBeInTheDocument();
  });

  it('subscribes to the ops topic and coalesces a burst into one refetch', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const connection = new FakeRealtimeConnection();
    renderWithProviders(<OperationsPage />, { connection });
    await vi.waitFor(() => expect(getOpsOverview).toHaveBeenCalledTimes(1));

    expect(connection.subscribedDestinations()).toEqual(['/topic/ops/orders']);

    // Every state change of every order lands on this topic; one refetch each would turn a
    // checkout burst into a refetch storm against the API the burst is already loading.
    for (let i = 0; i < 50; i++) {
      connection.emit('/topic/ops/orders', anEvent('FULFILLED', { orderId: `o-${i}` }));
    }
    expect(getOpsOverview).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(1200);
    await vi.waitFor(() => expect(getOpsOverview).toHaveBeenCalledTimes(2));

    vi.useRealTimers();
  });

  it('surfaces a load failure with a retry', async () => {
    vi.mocked(getOpsOverview).mockRejectedValue(new Error('overview unavailable'));
    renderWithProviders(<OperationsPage />);

    expect(await screen.findByRole('alert')).toHaveTextContent(/overview unavailable/);
  });

  it('shows recent orders with their totals', async () => {
    vi.mocked(getOpsOverview).mockResolvedValue(
      anOverview({
        recentOrders: [
          {
            orderId: 'order-recent-1',
            customerId: 'customer-7',
            status: 'FULFILLED',
            itemCount: 1,
            totalQuantity: 3,
            totalAmount: 59.97,
            version: 5,
            createdAt: '2026-09-03T08:00:00.000Z',
            updatedAt: '2026-09-03T08:01:00.000Z',
          },
        ],
      }),
    );
    renderWithProviders(<OperationsPage />);

    expect(await screen.findByText('order-re')).toBeInTheDocument();
    expect(screen.getByText('customer-7')).toBeInTheDocument();
    expect(screen.getByText('$59.97')).toBeInTheDocument();
  });

  it('shows an empty state when no orders exist yet', async () => {
    renderWithProviders(<OperationsPage />);

    await waitFor(() => expect(screen.getByText('No orders yet.')).toBeInTheDocument());
  });
});
