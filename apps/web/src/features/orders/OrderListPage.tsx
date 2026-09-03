import { useQuery } from '@tanstack/react-query';
import { useCallback, useState } from 'react';
import { Link } from 'react-router-dom';
import { listOrdersByCustomer, listOrdersByStatus } from '../../api/orders';
import { queryKeys } from '../../api/queryKeys';
import { EmptyState, Loading } from '../../components/Loading';
import { ErrorBanner } from '../../components/ErrorBanner';
import { StatusBadge } from '../../components/StatusBadge';
import { useOrderEvents } from '../../realtime/useRealtime';
import { topics } from '../../realtime/topics';
import { ORDER_STATUSES, type OrderStatus, type OrderStatusEvent } from '../../types';
import { formatMoney, formatRelative, shortId } from './format';
import { statusLabel } from './status';

type Filter = { kind: 'customer'; customerId: string } | { kind: 'status'; status: OrderStatus };

const DEFAULT_CUSTOMER = 'customer-1';

export function OrderListPage() {
  const [filter, setFilter] = useState<Filter>({ kind: 'customer', customerId: DEFAULT_CUSTOMER });
  const [customerInput, setCustomerInput] = useState(DEFAULT_CUSTOMER);

  const query = useQuery({
    queryKey:
      filter.kind === 'customer'
        ? queryKeys.ordersByCustomer(filter.customerId)
        : queryKeys.ordersByStatus(filter.status),
    queryFn: () =>
      filter.kind === 'customer'
        ? listOrdersByCustomer(filter.customerId, { limit: 25 })
        : listOrdersByStatus(filter.status, { limit: 25 }),
  });

  /**
   * An event means one of these rows changed status. Refetching the list rather than patching
   * the cached row keeps this correct under the status filter: a status change can move an
   * order *out of* the current result set, which a local patch cannot express. The list is
   * small and reconnects are rare, so a refetch is the cheaper thing to get right.
   */
  const onOrderEvent = useCallback(
    (_event: OrderStatusEvent) => {
      void query.refetch();
    },
    [query],
  );

  // Only the customer view has a topic scoped to it. The status view has no matching topic
  // (a status is not a subscription shape), so it relies on refetch-on-focus instead.
  useOrderEvents(
    filter.kind === 'customer' ? topics.customerOrders(filter.customerId) : null,
    onOrderEvent,
  );

  const orders = query.data?.items ?? [];

  return (
    <section className="page">
      <header className="page__header">
        <div>
          <h1>Orders</h1>
          <p className="page__subtitle">
            {filter.kind === 'customer'
              ? `Order history for ${filter.customerId}`
              : `Orders in ${statusLabel(filter.status)}`}
          </p>
        </div>
      </header>

      <div className="panel panel--toolbar">
        <form
          className="field-row"
          onSubmit={(event) => {
            event.preventDefault();
            const customerId = customerInput.trim();
            if (customerId) {
              setFilter({ kind: 'customer', customerId });
            }
          }}
        >
          <label className="field">
            <span className="field__label">Customer</span>
            <input
              className="input"
              value={customerInput}
              onChange={(event) => setCustomerInput(event.target.value)}
              placeholder="customer-1"
            />
          </label>
          <button type="submit" className="button">
            Show history
          </button>
        </form>

        <label className="field">
          <span className="field__label">Or filter by status</span>
          <select
            className="input"
            value={filter.kind === 'status' ? filter.status : ''}
            onChange={(event) => {
              const value = event.target.value;
              if (value) {
                setFilter({ kind: 'status', status: value as OrderStatus });
              } else {
                setFilter({ kind: 'customer', customerId: customerInput.trim() || DEFAULT_CUSTOMER });
              }
            }}
          >
            <option value="">— by customer —</option>
            {ORDER_STATUSES.map((status) => (
              <option key={status} value={status}>
                {statusLabel(status)}
              </option>
            ))}
          </select>
        </label>
      </div>

      {query.isError && <ErrorBanner error={query.error} onRetry={() => void query.refetch()} />}

      {query.isPending ? (
        <Loading label="Loading orders…" />
      ) : orders.length === 0 ? (
        <EmptyState>No orders match this filter yet.</EmptyState>
      ) : (
        <div className="panel">
          <table className="table">
            <thead>
              <tr>
                <th>Order</th>
                <th>Status</th>
                <th className="table__number">Items</th>
                <th className="table__number">Total</th>
                <th>Updated</th>
              </tr>
            </thead>
            <tbody>
              {orders.map((order) => (
                <tr key={order.orderId}>
                  <td>
                    <Link className="link" to={`/orders/${order.orderId}`}>
                      <code>{shortId(order.orderId)}</code>
                    </Link>
                    <small className="table__meta">{order.customerId}</small>
                  </td>
                  <td>
                    <StatusBadge status={order.status} />
                  </td>
                  <td className="table__number">{order.totalQuantity}</td>
                  <td className="table__number">{formatMoney(order.totalAmount)}</td>
                  <td>
                    <span title={order.updatedAt}>{formatRelative(order.updatedAt)}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          {query.data?.nextCursor && (
            <p className="panel__footnote">
              More orders exist beyond this page. Pagination lands with the detail view.
            </p>
          )}
        </div>
      )}
    </section>
  );
}
