import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useState } from 'react';
import { Link } from 'react-router-dom';
import { getFailures, getQueueHealth } from '../../api/operations';
import { cancelOrder } from '../../api/orders';
import { queryKeys } from '../../api/queryKeys';
import { ErrorBanner } from '../../components/ErrorBanner';
import { EmptyState, Loading } from '../../components/Loading';
import { StatTile } from '../../components/StatTile';
import { StatusBadge } from '../../components/StatusBadge';
import { useCoalescedCallback } from '../../hooks/useCoalescedCallback';
import { topics } from '../../realtime/topics';
import { useOrderEvents } from '../../realtime/useRealtime';
import { formatDateTime, formatMoney, shortId } from '../orders/format';
import { DlqRedrivePanel } from './DlqRedrivePanel';

const REFETCH_WINDOW_MS = 1_000;

export function FailuresPage() {
  const queryClient = useQueryClient();
  const [cancellingOrderId, setCancellingOrderId] = useState<string | null>(null);

  const failures = useQuery({
    queryKey: queryKeys.opsFailures,
    queryFn: () => getFailures(50),
  });

  const queueHealth = useQuery({
    queryKey: queryKeys.opsQueueHealth,
    queryFn: () => getQueueHealth(),
  });

  const refetch = useCoalescedCallback(
    useCallback(() => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.ops });
    }, [queryClient]),
    REFETCH_WINDOW_MS,
  );

  useOrderEvents(topics.opsOrders, refetch);

  /**
   * Cancelling from here is the operator resolution for an order parked in manual review: it
   * returns the held stock to the catalog. The button only appears when the server said the
   * order is cancellable, so the UI never offers an action the state machine would reject.
   */
  const cancel = useMutation({
    mutationFn: (orderId: string) => cancelOrder(orderId),
    onSettled: () => {
      setCancellingOrderId(null);
      void queryClient.invalidateQueries({ queryKey: queryKeys.ops });
      void queryClient.invalidateQueries({ queryKey: queryKeys.orderLists });
      void queryClient.invalidateQueries({ queryKey: queryKeys.inventory });
    },
  });

  const dlqDepth = queueHealth.data?.available
    ? queueHealth.data.deadLetterQueue?.visibleMessages ?? 0
    : null;

  return (
    <section className="page">
      <header className="page__header">
        <div>
          <h1>Failures</h1>
          <p className="page__subtitle">
            Orders whose fulfillment did not complete, with the reason recorded against each.
          </p>
        </div>
        <Link className="button" to="/operations">
          Back to overview
        </Link>
      </header>

      {cancel.isError && <ErrorBanner error={cancel.error} />}

      <div className="stat-row">
        <StatTile
          label="Failed orders"
          value={failures.data?.length ?? 0}
          tone={(failures.data?.length ?? 0) > 0 ? 'danger' : 'neutral'}
        />
        {dlqDepth === null ? (
          <div className="stat">
            <span className="stat__label">Dead-letter queue</span>
            <span className="stat__value stat__value--unknown">unknown</span>
            <span className="stat__hint">SQS could not be reached</span>
          </div>
        ) : (
          <StatTile
            label="Dead-letter queue"
            value={dlqDepth}
            tone={dlqDepth > 0 ? 'danger' : 'success'}
            hint="Messages that exhausted their retries"
          />
        )}
      </div>

      <DlqRedrivePanel dlqDepth={dlqDepth} />

      {failures.isError && (
        <ErrorBanner error={failures.error} onRetry={() => void failures.refetch()} />
      )}

      {failures.isPending ? (
        <Loading label="Loading failures…" />
      ) : (failures.data ?? []).length === 0 ? (
        <div className="panel">
          {/* "Nothing needs an operator" is only true if the DLQ is also clear: a dead-lettered
              message is operator work too, just the redrive workflow rather than this one. */}
          <EmptyState>
            {dlqDepth && dlqDepth > 0
              ? 'No permanently failed orders. The dead-letter queue above still has messages waiting to be redriven.'
              : 'No failed orders. Nothing needs an operator right now.'}
          </EmptyState>
        </div>
      ) : (
        <div className="panel">
          <table className="table">
            <thead>
              <tr>
                <th>Order</th>
                <th>Status</th>
                <th>Reason</th>
                <th className="table__number">Total</th>
                <th>Failed at</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {(failures.data ?? []).map((failure) => (
                <tr key={failure.orderId}>
                  <td>
                    <Link className="link" to={`/orders/${failure.orderId}`}>
                      <code>{shortId(failure.orderId)}</code>
                    </Link>
                    <small className="table__meta">{failure.customerId}</small>
                  </td>
                  <td>
                    <StatusBadge status={failure.status} />
                  </td>
                  <td>{failure.lastFailureReason ?? <span className="table__zero">—</span>}</td>
                  <td className="table__number">{formatMoney(failure.totalAmount)}</td>
                  <td>
                    <span title={failure.failedAt}>{formatDateTime(failure.failedAt)}</span>
                  </td>
                  <td>
                    {failure.cancellable && (
                      <button
                        type="button"
                        className="button button--danger button--compact"
                        disabled={cancel.isPending && cancellingOrderId === failure.orderId}
                        onClick={() => {
                          setCancellingOrderId(failure.orderId);
                          cancel.mutate(failure.orderId);
                        }}
                      >
                        {cancel.isPending && cancellingOrderId === failure.orderId
                          ? 'Releasing…'
                          : 'Cancel & release stock'}
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="panel__footnote">
            Cancelling releases the order&apos;s reserved inventory back to the catalog in one
            transaction. These orders failed permanently — for work interrupted by a transient
            fault, redrive the dead-letter queue above instead.
          </p>
        </div>
      )}
    </section>
  );
}
