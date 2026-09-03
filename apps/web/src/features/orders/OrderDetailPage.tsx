import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { cancelOrder, getOrder, getOrderAudit } from '../../api/orders';
import { queryKeys } from '../../api/queryKeys';
import { ErrorBanner } from '../../components/ErrorBanner';
import { Loading } from '../../components/Loading';
import { StatusBadge } from '../../components/StatusBadge';
import { topics } from '../../realtime/topics';
import { useOrderEvents } from '../../realtime/useRealtime';
import type { OrderStatusEvent } from '../../types';
import { formatDateTime, formatMoney, formatTime, shortId } from './format';
import { FULFILLMENT_STEPS, statusPresentation } from './status';

export function OrderDetailPage() {
  const { orderId } = useParams<{ orderId: string }>();
  const queryClient = useQueryClient();
  const location = useLocation();
  const replayed = (location.state as { replayed?: boolean } | null)?.replayed === true;

  /** Latency of the most recent live update, shown so the real-time path is visible. */
  const [lastEventLatencyMs, setLastEventLatencyMs] = useState<number | null>(null);

  const order = useQuery({
    queryKey: queryKeys.order(orderId ?? ''),
    queryFn: () => getOrder(orderId as string),
    enabled: Boolean(orderId),
  });

  const audit = useQuery({
    queryKey: queryKeys.orderAudit(orderId ?? ''),
    queryFn: () => getOrderAudit(orderId as string),
    enabled: Boolean(orderId),
  });

  /**
   * An event carries only the new status, so it triggers a refetch rather than patching the
   * cache. That is deliberate: the event is a hint that something changed, and DynamoDB is the
   * authority on what the order now looks like — version, timestamps and audit trail included.
   * Patching from the event would risk showing a status the server would not agree with.
   */
  const onOrderEvent = useCallback(
    (event: OrderStatusEvent) => {
      setLastEventLatencyMs(Date.now() - event.committedAtEpochMilli);
      void queryClient.invalidateQueries({ queryKey: queryKeys.order(event.orderId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.orderAudit(event.orderId) });
    },
    [queryClient],
  );

  useOrderEvents(orderId ? topics.order(orderId) : null, onOrderEvent);

  const cancel = useMutation({
    mutationFn: () => cancelOrder(orderId as string),
    onSuccess: (updated) => {
      // The cancel response is the authoritative post-cancel order, so seed the cache with it
      // rather than paying for a refetch. Only the list keys are invalidated — invalidating
      // all of `orders` would immediately discard what was just written here.
      queryClient.setQueryData(queryKeys.order(updated.orderId), updated);
      void queryClient.invalidateQueries({ queryKey: queryKeys.orderAudit(updated.orderId) });
      void queryClient.invalidateQueries({ queryKey: queryKeys.orderLists });
      void queryClient.invalidateQueries({ queryKey: queryKeys.inventory });
    },
  });

  if (!orderId) {
    return <ErrorBanner error={new Error('No order ID in the URL')} />;
  }

  if (order.isPending) {
    return <Loading label="Loading order…" />;
  }

  if (order.isError || !order.data) {
    return <ErrorBanner error={order.error} onRetry={() => void order.refetch()} />;
  }

  const data = order.data;
  const presentation = statusPresentation(data.status);

  return (
    <section className="page">
      <header className="page__header">
        <div>
          <h1>
            Order <code>{shortId(data.orderId)}</code>
          </h1>
          <p className="page__subtitle">
            {data.customerId} · placed {formatDateTime(data.createdAt)}
          </p>
        </div>
        <div className="page__actions">
          <StatusBadge status={data.status} />
          {data.cancellable && (
            <button
              type="button"
              className="button button--danger"
              disabled={cancel.isPending}
              onClick={() => cancel.mutate()}
            >
              {cancel.isPending ? 'Cancelling…' : 'Cancel order'}
            </button>
          )}
        </div>
      </header>

      {replayed && (
        <div className="banner banner--info">
          <div className="banner__body">
            <strong>Idempotent replay</strong>
            <span>
              This request reused an existing idempotency key, so the server returned the
              original order instead of creating a second one.
            </span>
          </div>
        </div>
      )}

      {cancel.isError && <ErrorBanner error={cancel.error} />}

      <div className="panel">
        <div className="panel__head">
          <h2 className="panel__title">Fulfillment</h2>
          {lastEventLatencyMs !== null && (
            <span className="pill" title="Time from the server committing the change to this page receiving it">
              live update +{lastEventLatencyMs} ms
            </span>
          )}
        </div>

        <p className="status-description">{presentation.description}</p>
        <ProgressTrack progress={presentation.progress} tone={presentation.tone} />
      </div>

      <div className="layout-split">
        <div className="panel">
          <h2 className="panel__title">Items</h2>
          <table className="table">
            <thead>
              <tr>
                <th>Item</th>
                <th className="table__number">Qty</th>
                <th className="table__number">Unit</th>
                <th className="table__number">Line total</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((item) => (
                <tr key={item.itemId}>
                  <td>{item.itemId}</td>
                  <td className="table__number">{item.quantity}</td>
                  <td className="table__number">{formatMoney(item.unitPrice)}</td>
                  <td className="table__number">{formatMoney(item.lineTotal)}</td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr>
                <th colSpan={3}>Total</th>
                <th className="table__number">{formatMoney(data.totalAmount)}</th>
              </tr>
            </tfoot>
          </table>
          <p className="panel__footnote">
            Unit prices are snapshotted at checkout, so a later catalog change cannot rewrite
            this order&apos;s value.
          </p>
        </div>

        <div className="panel">
          <h2 className="panel__title">Timeline</h2>
          {audit.isPending ? (
            <Loading label="Loading timeline…" />
          ) : audit.isError ? (
            <ErrorBanner error={audit.error} onRetry={() => void audit.refetch()} />
          ) : (
            <ol className="timeline">
              {(audit.data ?? []).map((entry) => (
                <li key={`${entry.timestamp}-${entry.toStatus}`} className="timeline__entry">
                  <span className="timeline__time" title={entry.timestamp}>
                    {formatTime(entry.timestamp)}
                  </span>
                  <div>
                    <strong>{entry.toStatus.replaceAll('_', ' ').toLowerCase()}</strong>
                    {entry.reason && <small className="timeline__reason">{entry.reason}</small>}
                  </div>
                </li>
              ))}
            </ol>
          )}
        </div>
      </div>

      <p className="page__footnote">
        Version {data.version} · last updated {formatDateTime(data.updatedAt)} ·{' '}
        <Link className="link" to="/orders">
          back to orders
        </Link>
      </p>
    </section>
  );
}

/**
 * Five-step fulfillment progress. A terminal non-success status has `progress === -1` and
 * renders as a single endpoint rather than a partly filled track — a failed order is not
 * "40% shipped".
 */
function ProgressTrack({ progress, tone }: { progress: number; tone: string }) {
  if (progress < 0) {
    return <div className={`track track--terminal track--${tone}`} />;
  }

  return (
    <ol className="track">
      {Array.from({ length: FULFILLMENT_STEPS }, (_, index) => {
        const step = index + 1;
        const state = step <= progress ? 'done' : step === progress + 1 ? 'next' : 'pending';
        return <li key={step} className={`track__step track__step--${state}`} />;
      })}
    </ol>
  );
}
