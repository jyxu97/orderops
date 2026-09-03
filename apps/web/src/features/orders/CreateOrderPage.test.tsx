import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../api/client';
import { CreateOrderPage } from './CreateOrderPage';
import { anInventoryItem, renderWithProviders } from '../../test/testUtils';

vi.mock('../../api/inventory');
vi.mock('../../api/orders');

const { listInventory } = await import('../../api/inventory');
const { createOrder } = await import('../../api/orders');

const navigate = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router-dom')>()),
  useNavigate: () => navigate,
}));

describe('CreateOrderPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(listInventory).mockResolvedValue([
      anInventoryItem(),
      anInventoryItem({ itemId: 'gizmo-pro', itemName: 'Gizmo Pro', unitPrice: 249, availableQuantity: 0 }),
    ]);
  });

  it('renders the catalog with prices and availability', async () => {
    renderWithProviders(<CreateOrderPage />);

    expect(await screen.findByText('Widget A')).toBeInTheDocument();
    expect(screen.getByText(/\$19\.99 · 100 available/)).toBeInTheDocument();
  });

  it('cannot add a sold-out item', async () => {
    renderWithProviders(<CreateOrderPage />);
    await screen.findByText('Gizmo Pro');

    expect(screen.getByRole('button', { name: 'Sold out' })).toBeDisabled();
  });

  it('will not submit an empty basket', async () => {
    renderWithProviders(<CreateOrderPage />);
    await screen.findByText('Widget A');

    expect(screen.getByRole('button', { name: 'Place order' })).toBeDisabled();
  });

  it('places an order and navigates to its detail page', async () => {
    const user = userEvent.setup();
    vi.mocked(createOrder).mockResolvedValue({
      orderId: 'order-9',
      status: 'INVENTORY_RESERVED',
      totalAmount: 19.99,
      createdAt: '2026-09-03T08:00:00.000Z',
      replayed: false,
    });

    renderWithProviders(<CreateOrderPage />);
    await screen.findByText('Widget A');
    await user.click(screen.getByRole('button', { name: 'Add' }));
    await user.click(screen.getByRole('button', { name: 'Place order' }));

    await waitFor(() => expect(createOrder).toHaveBeenCalledTimes(1));
    expect(createOrder).toHaveBeenCalledWith(
      { customerId: 'customer-1', items: [{ itemId: 'widget-a', quantity: 1 }] },
      expect.any(String),
    );
    await waitFor(() =>
      expect(navigate).toHaveBeenCalledWith('/orders/order-9', { state: { replayed: false } }),
    );
  });

  it('shows the backend message when stock ran out', async () => {
    const user = userEvent.setup();
    vi.mocked(createOrder).mockRejectedValue(
      new ApiError(
        409,
        { status: 409, error: 'Conflict', message: 'Insufficient inventory for itemId=widget-a, requested=1' },
        'failed',
      ),
    );

    renderWithProviders(<CreateOrderPage />);
    await screen.findByText('Widget A');
    await user.click(screen.getByRole('button', { name: 'Add' }));
    await user.click(screen.getByRole('button', { name: 'Place order' }));

    // The specific reason matters: "insufficient inventory" and "idempotency key reused" are
    // both 409s a user has to tell apart.
    expect(await screen.findByRole('alert')).toHaveTextContent(/Insufficient inventory/);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('reuses the same idempotency key when a failed submit is retried', async () => {
    const user = userEvent.setup();
    vi.mocked(createOrder).mockRejectedValue(new Error('network timeout'));

    renderWithProviders(<CreateOrderPage />);
    await screen.findByText('Widget A');
    await user.click(screen.getByRole('button', { name: 'Add' }));

    await user.click(screen.getByRole('button', { name: 'Place order' }));
    await screen.findByRole('alert');
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(createOrder).toHaveBeenCalledTimes(2));

    // The whole point of the key: a retry after a timeout must be recognised as the same
    // order, not reserve stock a second time.
    const firstKey = vi.mocked(createOrder).mock.calls[0]?.[1];
    const secondKey = vi.mocked(createOrder).mock.calls[1]?.[1];
    expect(firstKey).toBeTruthy();
    expect(secondKey).toBe(firstKey);
  });

  it('mints a new idempotency key once the basket changes', async () => {
    const user = userEvent.setup();
    vi.mocked(createOrder).mockRejectedValue(new Error('network timeout'));

    renderWithProviders(<CreateOrderPage />);
    await screen.findByText('Widget A');
    await user.click(screen.getByRole('button', { name: 'Add' }));
    await user.click(screen.getByRole('button', { name: 'Place order' }));
    await screen.findByRole('alert');

    // Editing the basket makes this a different order, so it must not inherit the old key —
    // otherwise the server would replay the previous request and ignore the change.
    await user.clear(screen.getByLabelText('Quantity for widget-a'));
    await user.type(screen.getByLabelText('Quantity for widget-a'), '3');
    await user.click(screen.getByRole('button', { name: 'Place order' }));

    await waitFor(() => expect(createOrder).toHaveBeenCalledTimes(2));
    expect(vi.mocked(createOrder).mock.calls[1]?.[1]).not.toBe(
      vi.mocked(createOrder).mock.calls[0]?.[1],
    );
  });

  it('shows a reserving state and never claims success early', async () => {
    const user = userEvent.setup();
    let resolve: (() => void) | undefined;
    vi.mocked(createOrder).mockReturnValue(
      new Promise((res) => {
        resolve = () =>
          res({
            orderId: 'order-9',
            status: 'INVENTORY_RESERVED',
            totalAmount: 19.99,
            createdAt: '2026-09-03T08:00:00.000Z',
            replayed: false,
          });
      }),
    );

    renderWithProviders(<CreateOrderPage />);
    await screen.findByText('Widget A');
    await user.click(screen.getByRole('button', { name: 'Add' }));
    await user.click(screen.getByRole('button', { name: 'Place order' }));

    // Inventory is exactly the thing that cannot be optimistically claimed.
    expect(await screen.findByRole('button', { name: 'Reserving stock…' })).toBeDisabled();
    expect(navigate).not.toHaveBeenCalled();

    resolve?.();
    await waitFor(() => expect(navigate).toHaveBeenCalled());
  });

  it('totals the basket from catalog prices', async () => {
    const user = userEvent.setup();
    renderWithProviders(<CreateOrderPage />);
    await screen.findByText('Widget A');
    await user.click(screen.getByRole('button', { name: 'Add' }));

    await user.clear(screen.getByLabelText('Quantity for widget-a'));
    await user.type(screen.getByLabelText('Quantity for widget-a'), '3');

    // Twice on purpose: the line total in the row and the basket total in the footer. A single
    // item basket makes them equal, and both must track the quantity.
    await waitFor(() => expect(screen.getAllByText('$59.97')).toHaveLength(2));
  });
});
