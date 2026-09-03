import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getDlqRedriveStatus, startDlqRedrive } from '../../api/operations';
import { queryKeys } from '../../api/queryKeys';
import { ErrorBanner } from '../../components/ErrorBanner';
import type { DlqRedrive } from '../../types';
import { formatDateTime } from '../orders/format';

interface Props {
  /** Current DLQ depth, or null when SQS could not be read. */
  dlqDepth: number | null;
}

/** While a task is running, SQS's counters are the only progress signal, so poll them. */
const RUNNING_POLL_MS = 2_000;

function isRunning(redrive: DlqRedrive | undefined): boolean {
  return redrive?.status === 'RUNNING' || redrive?.status === 'CANCELLING';
}

/**
 * Operator control for putting dead-lettered messages back on the fulfillment queue.
 *
 * A redrive recovers orders whose fulfillment was interrupted by a transient fault that
 * outlived its retry budget — those orders are parked mid-flight and the worker resumes them
 * from where they stopped. It is not the remedy for an order in manual review: that one failed
 * permanently, and the action there is to cancel and release its stock.
 */
export function DlqRedrivePanel({ dlqDepth }: Props) {
  const queryClient = useQueryClient();

  const redrive = useQuery({
    queryKey: queryKeys.opsDlqRedrive,
    queryFn: () => getDlqRedriveStatus(),
    // Only poll while something is moving; an idle dashboard should not generate traffic.
    refetchInterval: (query) => (isRunning(query.state.data) ? RUNNING_POLL_MS : false),
  });

  const start = useMutation({
    mutationFn: () => startDlqRedrive(),
    onSuccess: (started) => {
      queryClient.setQueryData(queryKeys.opsDlqRedrive, started);
      void queryClient.invalidateQueries({ queryKey: queryKeys.opsQueueHealth });
    },
  });

  const task = redrive.data;
  const running = isRunning(task);
  const nothingToMove = dlqDepth === 0;

  return (
    <div className="panel">
      <div className="panel__head">
        <h2 className="panel__title">Dead-letter redrive</h2>
        {task && task.status !== 'NONE' && (
          <span className={`badge badge--${task.status === 'FAILED' ? 'danger' : task.status === 'COMPLETED' ? 'success' : 'active'}`}>
            {task.status.toLowerCase()}
          </span>
        )}
      </div>

      {start.isError && <ErrorBanner error={start.error} />}

      <p className="panel__footnote">
        Puts dead-lettered messages back on the fulfillment queue. Their orders are parked
        mid-flight after a transient fault outlived its retries, and the worker resumes each one
        from where it stopped. An order in manual review failed permanently — cancel it instead.
      </p>

      {task && task.status !== 'NONE' && (
        <dl className="kv">
          <div className="kv__row">
            <dt>Moved</dt>
            <dd>
              {(task.messagesMoved ?? 0).toLocaleString()}
              {task.messagesToMove != null && ` of ${task.messagesToMove.toLocaleString()}`}
            </dd>
          </div>
          {task.maxMessagesPerSecond != null && (
            <div className="kv__row">
              <dt>Rate limit</dt>
              <dd>{task.maxMessagesPerSecond}/second</dd>
            </div>
          )}
          {task.startedAt && (
            <div className="kv__row">
              <dt>Started</dt>
              <dd>{formatDateTime(task.startedAt)}</dd>
            </div>
          )}
          {task.failureReason && (
            <div className="kv__row">
              <dt>Failure</dt>
              <dd>{task.failureReason}</dd>
            </div>
          )}
        </dl>
      )}

      <div className="panel__footer">
        <span className="panel__footnote">
          {dlqDepth === null
            ? 'DLQ depth unknown — SQS could not be reached.'
            : nothingToMove
              ? 'Nothing in the dead-letter queue.'
              : `${dlqDepth.toLocaleString()} message(s) waiting.`}
        </span>
        <button
          type="button"
          className="button button--primary"
          // Disabled on an empty DLQ and while one is in flight: SQS allows a single move task
          // per queue, so a second click would only produce a 409.
          disabled={start.isPending || running || nothingToMove || dlqDepth === null}
          onClick={() => start.mutate()}
        >
          {running ? 'Redriving…' : start.isPending ? 'Starting…' : 'Redrive queue'}
        </button>
      </div>
    </div>
  );
}
