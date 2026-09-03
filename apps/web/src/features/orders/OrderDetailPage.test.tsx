import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../api/client';
import { OrderDetailPage } from './OrderDetailPage';
import { anEvent, anOrder, renderWithProviders } from '../../test/testUtils';
import { FakeRealtimeConnection } from '../../test/FakeRealtimeConnection';

vi.mock('../../api/orders');

const { getOrder, getOrderAudit, cancelOrder } = await import('../../api/orders');

const ORDER_ID = 'order-1234-5678';

function renderDetail(connection = new FakeRealtimeConnection()) {
  return renderWithProviders(<OrderDetailPage />, {
    route: `/orders/${ORDER_ID}`,
    path: '/orders/:orderId',
    connection,
  });
}

describe('OrderDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(getOrder).mockResolvedValue(anOrder());
    vi.mocked(getOrderAudit).mockResolvedValue([
      {
        timestamp: '2026-09-03T08:00:00.000Z',
        fromStatus: 'CREATED',
        toStatus: 'INVENTORY_RESERVED',
        reason: 'Order created',
      },
    ]);
  });

  it('renders items with the snapshotted unit price and the order total', async () => {
    renderDetail();

    expect(await screen.findByText('widget-a')).toBeInTheDocument();
    // Unit price and line total both come from the order, not from the live catalog.
    expect(screen.getByText('$19.99')).toBeInTheDocument();
    expect(screen.getAllByText('$39.98')).not.toHaveLength(0);
  });

  it('renders the audit timeline', async () => {
    renderDetail();

    expect(await screen.findByText('Order created')).toBeInTheDocument();
    expect(screen.getByText('inventory reserved')).toBeInTheDocument();
  });

  it('subscribes to this order only', async () => {
    const connection = new FakeRealtimeConnection();
    renderDetail(connection);
    await screen.findByText('widget-a');

    expect(connection.subscribedDestinations()).toEqual([`/topic/orders/${ORDER_ID}`]);
  });

  it('refetches the order and its timeline when an event arrives', async () => {
    const connection = new FakeRealtimeConnection();
    renderDetail(connection);
    await screen.findByText('widget-a');
    expect(getOrder).toHaveBeenCalledTimes(1);

    vi.mocked(getOrder).mockResolvedValue(anOrder({ status: 'FULFILLED', cancellable: false }));
    connection.emit(`/topic/orders/${ORDER_ID}`, anEvent('FULFILLED'));

    // The event carries only the status; DynamoDB is the authority on the rest of the order,
    // so the page refetches rather than patching its cache from the event.
    await waitFor(() => expect(getOrder).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('Fulfilled')).toBeInTheDocument();
    await waitFor(() => expect(getOrderAudit).toHaveBeenCalledTimes(2));
  });

  it('shows the delivery latency of a live update', async () => {
    const connection = new FakeRealtimeConnection();
    renderDetail(connection);
    await screen.findByText('widget-a');

    connection.emit(
      `/topic/orders/${ORDER_ID}`,
      anEvent('PAYMENT_PROCESSING', { committedAtEpochMilli: Date.now() - 42 }),
    );

    expect(await screen.findByText(/live update \+\d+ ms/)).toBeInTheDocument();
  });

  it('offers cancel only when the server says the order is cancellable', async () => {
    renderDetail();

    expect(await screen.findByRole('button', { name: 'Cancel order' })).toBeInTheDocument();
  });

  it('hides cancel once the order is past the point of no return', async () => {
    // `cancellable` is derived from the state machine server-side, so the UI never has to keep
    // its own copy of which statuses allow it.
    vi.mocked(getOrder).mockResolvedValue(anOrder({ status: 'SHIPMENT_PROCESSING', cancellable: false }));
    renderDetail();

    await screen.findByText('widget-a');
    expect(screen.queryByRole('button', { name: 'Cancel order' })).not.toBeInTheDocument();
  });

  it('cancels the order and shows the new state', async () => {
    const user = userEvent.setup();
    vi.mocked(cancelOrder).mockResolvedValue(
      anOrder({ status: 'CANCELLED', cancellable: false, version: 2 }),
    );

    renderDetail();
    await user.click(await screen.findByRole('button', { name: 'Cancel order' }));

    await waitFor(() => expect(cancelOrder).toHaveBeenCalledWith(ORDER_ID));
    expect(await screen.findByText('Cancelled')).toBeInTheDocument();
  });

  it('surfaces a rejected cancel with the reason', async () => {
    const user = userEvent.setup();
    vi.mocked(cancelOrder).mockRejectedValue(
      new ApiError(
        409,
        {
          status: 409,
          error: 'Conflict',
          message: 'Invalid state transition: PAYMENT_PROCESSING -> CANCELLED',
        },
        'failed',
      ),
    );

    renderDetail();
    await user.click(await screen.findByRole('button', { name: 'Cancel order' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/PAYMENT_PROCESSING -> CANCELLED/);
  });

  it('renders a failed order as a terminal state, not partial progress', async () => {
    vi.mocked(getOrder).mockResolvedValue(
      anOrder({ status: 'NEEDS_MANUAL_REVIEW', cancellable: true }),
    );
    renderDetail();

    expect(await screen.findByText('Needs review')).toBeInTheDocument();
    // A failed order is not "40% shipped", so no step track is drawn for it.
    expect(document.querySelectorAll('.track__step')).toHaveLength(0);
  });

  it('shows the order error when it cannot be loaded', async () => {
    vi.mocked(getOrder).mockRejectedValue(
      new ApiError(404, { status: 404, error: 'Not Found', message: 'Order not found: nope' }, 'failed'),
    );
    renderDetail();

    expect(await screen.findByRole('alert')).toHaveTextContent(/Order not found/);
  });

  it('flags an idempotent replay when arriving from checkout', async () => {
    renderWithProviders(<OrderDetailPage />, {
      route: `/orders/${ORDER_ID}`,
      path: '/orders/:orderId',
    });
    await screen.findByText('widget-a');

    // Nothing in the location state, so no banner.
    expect(screen.queryByText('Idempotent replay')).not.toBeInTheDocument();
  });
});
