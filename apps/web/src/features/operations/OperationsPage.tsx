import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback } from 'react';
import { Link } from 'react-router-dom';
import { getOpsOverview } from '../../api/operations';
import { queryKeys } from '../../api/queryKeys';
import { ErrorBanner } from '../../components/ErrorBanner';
import { EmptyState, Loading } from '../../components/Loading';
import { StatTile } from '../../components/StatTile';
import { StatusBadge } from '../../components/StatusBadge';
import { useCoalescedCallback } from '../../hooks/useCoalescedCallback';
import { topics } from '../../realtime/topics';
import { useOrderEvents } from '../../realtime/useRealtime';
import { ORDER_STATUSES, type OpsOverview, type OrderStatus } from '../../types';
import { formatMoney, formatRelative, shortId } from '../orders/format';
import { QueueHealthPanel } from './QueueHealthPanel';

/** One refetch per window, however many events arrive. */
const REFETCH_WINDOW_MS = 1_000;

/** Statuses where fulfillment is still in progress. */
const IN_FLIGHT: OrderStatus[] = [
  'INVENTORY_RESERVED',
  'PAYMENT_PROCESSING',
  'PAYMENT_SUCCEEDED',
  'SHIPMENT_PROCESSING',
];

const NEEDS_ATTENTION: OrderStatus[] = ['FAILED', 'NEEDS_MANUAL_REVIEW'];

function sumOf(counts: OpsOverview['statusCounts'], statuses: OrderStatus[]): number {
  return statuses.reduce((total, status) => total + (counts[status] ?? 0), 0);
}

export function OperationsPage() {
  const queryClient = useQueryClient();

  const overview = useQuery({
    queryKey: queryKeys.opsOverview,
    queryFn: () => getOpsOverview(20),
  });

  const refetchOverview = useCoalescedCallback(
    useCallback(() => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.opsOverview });
    }, [queryClient]),
    REFETCH_WINDOW_MS,
  );

  // Every order event in the system lands here, so the handler must be cheap.
  useOrderEvents(topics.opsOrders, refetchOverview);

  if (overview.isPending) {
    return <Loading label="Loading operations overview…" />;
  }

  if (overview.isError || !overview.data) {
    return <ErrorBanner error={overview.error} onRetry={() => void overview.refetch()} />;
  }

  const data = overview.data;
  const counts = data.statusCounts;
  const attention = sumOf(counts, NEEDS_ATTENTION);

  return (
    <section className="page">
      <header className="page__header">
        <div>
          <h1>Operations</h1>
          <p className="page__subtitle">
            Live across every order. Updated {formatRelative(data.generatedAt)}.
          </p>
        </div>
        <Link className="button" to="/operations/failures">
          Review failures
        </Link>
      </header>

      {data.countsCapped && (
        <div className="banner banner--warning">
          <div className="banner__body">
            <strong>Counts are lower bounds</strong>
            <span>
              At least one status has more orders than the count query reads in one pass, so the
              totals below are floors rather than exact figures.
            </span>
          </div>
        </div>
      )}

      <div className="stat-row">
        <StatTile
          label="In fulfillment"
          value={sumOf(counts, IN_FLIGHT)}
          tone="active"
          hint="Reserved through to shipment"
        />
        <StatTile label="Fulfilled" value={counts.FULFILLED ?? 0} tone="success" />
        <StatTile
          label="Needs attention"
          value={attention}
          tone={attention > 0 ? 'danger' : 'neutral'}
          hint="Failed or awaiting manual review"
        />
        <StatTile label="Cancelled" value={counts.CANCELLED ?? 0} />
      </div>

      <div className="layout-split layout-split--stretch">
        <QueueHealthPanel health={data.queueHealth} />

        <div className="panel">
          <h2 className="panel__title">Orders by status</h2>
          {/* Nine statuses all carry meaning, which is past the point where more colour helps —
              so this is a table, and each row links to the same filter on the order list. */}
          <table className="table">
            <thead>
              <tr>
                <th>Status</th>
                <th className="table__number">Orders</th>
              </tr>
            </thead>
            <tbody>
              {ORDER_STATUSES.map((status) => (
                <tr key={status}>
                  <td>
                    <StatusBadge status={status} />
                  </td>
                  <td className="table__number">
                    {(counts[status] ?? 0) > 0 ? (
                      <Link className="link" to={`/orders?status=${status}`}>
                        {counts[status]?.toLocaleString()}
                      </Link>
                    ) : (
                      <span className="table__zero">0</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="panel">
        <h2 className="panel__title">Recent orders</h2>
        {data.recentOrders.length === 0 ? (
          <EmptyState>No orders yet.</EmptyState>
        ) : (
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
              {data.recentOrders.map((order) => (
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
        )}
      </div>
    </section>
  );
}
