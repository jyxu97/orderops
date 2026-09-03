import type { QueueHealth } from '../../types';

interface Props {
  health: QueueHealth;
}

/**
 * Queue depth as two meters plus the threshold breaches behind an unhealthy verdict.
 *
 * A meter rather than a chart: each row is one value against one limit, which is the case the
 * form heuristic answers with a meter and not a two-slice pie or a one-bar bar chart.
 */
export function QueueHealthPanel({ health }: Props) {
  if (!health.available) {
    return (
      <div className="panel">
        <h2 className="panel__title">Queue depth</h2>
        {/* Never rendered as healthy: an unread metric is unknown, not fine. */}
        <p className="empty-state">
          Queue depth is unavailable — SQS could not be reached.
          {health.unavailableReason && (
            <>
              {' '}
              <code>{health.unavailableReason}</code>
            </>
          )}
        </p>
      </div>
    );
  }

  const threshold = health.backlogThreshold ?? 100;
  const queue = health.queue;
  const dlq = health.deadLetterQueue;
  const warnings = health.warnings ?? [];

  return (
    <div className="panel">
      <div className="panel__head">
        <h2 className="panel__title">Queue depth</h2>
        <span className={`badge badge--${health.healthy ? 'success' : 'danger'}`}>
          {health.healthy ? 'Healthy' : 'Attention needed'}
        </span>
      </div>

      {queue && (
        <Meter
          label="Fulfillment backlog"
          value={queue.visibleMessages}
          limit={threshold}
          limitLabel={`threshold ${threshold}`}
          detail={`${queue.inFlightMessages} in flight · ${queue.delayedMessages} delayed`}
        />
      )}

      {dlq && (
        <Meter
          label="Dead-letter queue"
          value={dlq.visibleMessages}
          // Any DLQ message means a fulfillment exhausted its retries, so the bar is full at
          // one message rather than scaled against a tolerance that does not exist.
          limit={1}
          limitLabel="tolerance 0"
          detail={
            dlq.visibleMessages === 0
              ? 'No messages have exhausted their retries'
              : `${dlq.visibleMessages} message(s) awaiting an operator`
          }
        />
      )}

      {warnings.length > 0 && (
        <ul className="warnings">
          {warnings.map((warning) => (
            <li key={warning} className="warnings__item">
              <span className="warnings__mark" aria-hidden="true" />
              {warning}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

interface MeterProps {
  label: string;
  value: number;
  limit: number;
  limitLabel: string;
  detail: string;
}

/**
 * The fill carries severity and the track is a lighter step of the same ramp, so the state
 * reads across the whole bar rather than only where the fill happens to end.
 */
function Meter({ label, value, limit, limitLabel, detail }: MeterProps) {
  const ratio = limit <= 0 ? 0 : Math.min(1, value / limit);
  const severity = ratio >= 1 ? 'danger' : ratio >= 0.7 ? 'warning' : 'ok';

  return (
    <div className="meter">
      <div className="meter__head">
        <span className="meter__label">{label}</span>
        {/* Value in ink, not the severity colour — severity is on the bar. */}
        <span className="meter__value">
          {value.toLocaleString()}
          <small className="meter__limit"> / {limitLabel}</small>
        </span>
      </div>
      <div
        className={`meter__track meter__track--${severity}`}
        role="meter"
        aria-valuenow={value}
        aria-valuemin={0}
        aria-valuemax={limit}
        aria-label={label}
      >
        <span className={`meter__fill meter__fill--${severity}`} style={{ width: `${ratio * 100}%` }} />
      </div>
      <span className="meter__detail">{detail}</span>
    </div>
  );
}
