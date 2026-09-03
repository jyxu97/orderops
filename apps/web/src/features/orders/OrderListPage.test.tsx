import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { OrderListPage } from './OrderListPage';
import { anEvent, renderWithProviders } from '../../test/testUtils';
import { FakeRealtimeConnection } from '../../test/FakeRealtimeConnection';
import type { OrderSummary } from '../../types';

vi.mock('../../api/orders');

const { listOrdersByCustomer, listOrdersByStatus } = await import('../../api/orders');

function aSummary(overrides: Partial<OrderSummary> = {}): OrderSummary {
  return {
    orderId: 'order-aaaa-bbbb',
    customerId: 'customer-1',
    status: 'INVENTORY_RESERVED',
    itemCount: 1,
    totalQuantity: 2,
    totalAmount: 39.98,
    version: 1,
    createdAt: '2026-09-03T08:00:00.000Z',
    updatedAt: '2026-09-03T08:00:00.000Z',
    ...overrides,
  };
}

describe('OrderListPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(listOrdersByCustomer).mockResolvedValue({ items: [aSummary()] });
    vi.mocked(listOrdersByStatus).mockResolvedValue({
      items: [aSummary({ orderId: 'order-failed-1', status: 'NEEDS_MANUAL_REVIEW' })],
    });
  });

  it('lists a customer history by default', async () => {
    renderWithProviders(<OrderListPage />);

    expect(await screen.findByText('order-aa')).toBeInTheDocument();
    expect(listOrdersByCustomer).toHaveBeenCalledWith('customer-1', { limit: 25 });
    expect(listOrdersByStatus).not.toHaveBeenCalled();
  });

  it('switches to another customer', async () => {
    const user = userEvent.setup();
    renderWithProviders(<OrderListPage />);
    await screen.findByText('order-aa');

    await user.clear(screen.getByRole('textbox'));
    await user.type(screen.getByRole('textbox'), 'customer-9');
    await user.click(screen.getByRole('button', { name: 'Show history' }));

    await waitFor(() =>
      expect(listOrdersByCustomer).toHaveBeenCalledWith('customer-9', { limit: 25 }),
    );
  });

  it('filters by status', async () => {
    const user = userEvent.setup();
    renderWithProviders(<OrderListPage />);
    await screen.findByText('order-aa');

    await user.selectOptions(screen.getByRole('combobox'), 'NEEDS_MANUAL_REVIEW');

    await waitFor(() =>
      expect(listOrdersByStatus).toHaveBeenCalledWith('NEEDS_MANUAL_REVIEW', { limit: 25 }),
    );
    expect(await screen.findByText('order-fa')).toBeInTheDocument();
  });

  it('honours a ?status= deep link from the operations dashboard', async () => {
    renderWithProviders(<OrderListPage />, { route: '/orders?status=FAILED' });

    await waitFor(() => expect(listOrdersByStatus).toHaveBeenCalledWith('FAILED', { limit: 25 }));
    expect(listOrdersByCustomer).not.toHaveBeenCalled();
  });

  it('ignores an unknown status in the URL rather than querying for it', async () => {
    // The backend would reject it with a 400; not sending it at all is better than rendering
    // an error the user did not cause.
    renderWithProviders(<OrderListPage />, { route: '/orders?status=NOT_A_STATUS' });

    await waitFor(() => expect(listOrdersByCustomer).toHaveBeenCalled());
    expect(listOrdersByStatus).not.toHaveBeenCalled();
  });

  it('subscribes to the customer topic, and to nothing while filtering by status', async () => {
    const user = userEvent.setup();
    const connection = new FakeRealtimeConnection();
    renderWithProviders(<OrderListPage />, { connection });
    await screen.findByText('order-aa');

    expect(connection.subscribedDestinations()).toEqual(['/topic/customers/customer-1/orders']);

    // A status is not a subscription shape the server allows, so the status view has no topic
    // to watch and relies on refetch instead.
    await user.selectOptions(screen.getByRole('combobox'), 'FAILED');
    await waitFor(() => expect(connection.subscribedDestinations()).toEqual([]));
  });

  it('refetches when one of the customer&apos;s orders changes', async () => {
    const connection = new FakeRealtimeConnection();
    renderWithProviders(<OrderListPage />, { connection });
    await screen.findByText('order-aa');
    expect(listOrdersByCustomer).toHaveBeenCalledTimes(1);

    connection.emit('/topic/customers/customer-1/orders', anEvent('FULFILLED'));

    await waitFor(() => expect(listOrdersByCustomer).toHaveBeenCalledTimes(2));
  });

  it('shows an empty state rather than a blank table', async () => {
    vi.mocked(listOrdersByCustomer).mockResolvedValue({ items: [] });
    renderWithProviders(<OrderListPage />);

    expect(await screen.findByText(/No orders match this filter/)).toBeInTheDocument();
  });

  it('surfaces a load failure with a retry', async () => {
    vi.mocked(listOrdersByCustomer).mockRejectedValue(new Error('network down'));
    renderWithProviders(<OrderListPage />);

    expect(await screen.findByRole('alert')).toHaveTextContent(/network down/);
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});
