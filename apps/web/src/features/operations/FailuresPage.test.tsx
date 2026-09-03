import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../api/client';
import { FailuresPage } from './FailuresPage';
import { anOrder, renderWithProviders } from '../../test/testUtils';
import type { DlqRedrive, FailedOrder, QueueHealth } from '../../types';

vi.mock('../../api/operations');
vi.mock('../../api/orders');

const { getFailures, getQueueHealth, getDlqRedriveStatus, startDlqRedrive } =
  await import('../../api/operations');
const { cancelOrder } = await import('../../api/orders');

function aFailure(overrides: Partial<FailedOrder> = {}): FailedOrder {
  return {
    orderId: 'order-failed-1',
    customerId: 'customer-3',
    status: 'NEEDS_MANUAL_REVIEW',
    totalAmount: 249,
    lastFailureReason: 'Payment declined',
    failedAt: '2026-09-03T08:30:30.000Z',
    cancellable: true,
    createdAt: '2026-09-03T08:30:27.000Z',
    updatedAt: '2026-09-03T08:30:30.000Z',
    ...overrides,
  };
}

function queueHealth(dlqVisible: number): QueueHealth {
  return {
    available: true,
    healthy: dlqVisible === 0,
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
      visibleMessages: dlqVisible,
      inFlightMessages: 0,
      delayedMessages: 0,
    },
  };
}

const NO_REDRIVE: DlqRedrive = { status: 'NONE' };

describe('FailuresPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(getFailures).mockResolvedValue([aFailure()]);
    vi.mocked(getQueueHealth).mockResolvedValue(queueHealth(0));
    vi.mocked(getDlqRedriveStatus).mockResolvedValue(NO_REDRIVE);
  });

  it('shows the cause of each failure, not the routing step', async () => {
    renderWithProviders(<FailuresPage />);

    // The newest audit entry is "Queued for manual review", which explains nothing.
    expect(await screen.findByText('Payment declined')).toBeInTheDocument();
    expect(screen.getByText('customer-3')).toBeInTheDocument();
  });

  it('renders a dash when no reason was recorded', async () => {
    vi.mocked(getFailures).mockResolvedValue([aFailure({ lastFailureReason: undefined })]);
    renderWithProviders(<FailuresPage />);

    await screen.findByText('order-fa');
    expect(screen.getByText('—')).toBeInTheDocument();
  });

  it('cancels a reviewable order to release its stock', async () => {
    const user = userEvent.setup();
    vi.mocked(cancelOrder).mockResolvedValue(anOrder({ status: 'CANCELLED', cancellable: false }));

    renderWithProviders(<FailuresPage />);
    await user.click(await screen.findByRole('button', { name: 'Cancel & release stock' }));

    await waitFor(() => expect(cancelOrder).toHaveBeenCalledWith('order-failed-1'));
  });

  it('offers no cancel for an order the state machine will not allow it on', async () => {
    vi.mocked(getFailures).mockResolvedValue([aFailure({ cancellable: false })]);
    renderWithProviders(<FailuresPage />);

    await screen.findByText('order-fa');
    expect(screen.queryByRole('button', { name: /Cancel & release/ })).not.toBeInTheDocument();
  });

  it('surfaces a rejected cancel', async () => {
    const user = userEvent.setup();
    vi.mocked(cancelOrder).mockRejectedValue(
      new ApiError(409, { status: 409, error: 'Conflict', message: 'no longer cancellable' }, 'failed'),
    );

    renderWithProviders(<FailuresPage />);
    await user.click(await screen.findByRole('button', { name: 'Cancel & release stock' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/no longer cancellable/);
  });

  // ── Redrive ───────────────────────────────────────────────────────────────

  it('disables redrive when the dead-letter queue is empty', async () => {
    renderWithProviders(<FailuresPage />);

    // The panel renders before queue health resolves, so wait for the settled copy.
    expect(await screen.findByText('Nothing in the dead-letter queue.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Redrive queue' })).toBeDisabled();
  });

  it('enables redrive once messages are waiting, and reports how many', async () => {
    vi.mocked(getQueueHealth).mockResolvedValue(queueHealth(3));
    renderWithProviders(<FailuresPage />);

    expect(await screen.findByText('3 message(s) waiting.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Redrive queue' })).toBeEnabled();
  });

  it('starts a redrive and shows it running', async () => {
    const user = userEvent.setup();
    vi.mocked(getQueueHealth).mockResolvedValue(queueHealth(5));
    vi.mocked(startDlqRedrive).mockResolvedValue({
      status: 'RUNNING',
      messagesMoved: 1,
      messagesToMove: 5,
      maxMessagesPerSecond: 10,
      startedAt: '2026-09-03T08:45:48.000Z',
    });

    renderWithProviders(<FailuresPage />);
    await user.click(await screen.findByRole('button', { name: 'Redrive queue' }));

    await waitFor(() => expect(startDlqRedrive).toHaveBeenCalledTimes(1));
    expect(await screen.findByText('running')).toBeInTheDocument();
    expect(screen.getByText('1 of 5')).toBeInTheDocument();
    // Shown so an operator can see the redrive cannot outrun the worker.
    expect(screen.getByText('10/second')).toBeInTheDocument();
  });

  it('disables the button while a redrive is already running', async () => {
    vi.mocked(getQueueHealth).mockResolvedValue(queueHealth(5));
    vi.mocked(getDlqRedriveStatus).mockResolvedValue({
      status: 'RUNNING',
      messagesMoved: 2,
      messagesToMove: 5,
    });

    renderWithProviders(<FailuresPage />);

    // SQS permits a single move task per queue, so a second click could only yield a 409.
    expect(await screen.findByRole('button', { name: 'Redriving…' })).toBeDisabled();
  });

  it('surfaces the 409 when a redrive was already started elsewhere', async () => {
    const user = userEvent.setup();
    vi.mocked(getQueueHealth).mockResolvedValue(queueHealth(5));
    vi.mocked(startDlqRedrive).mockRejectedValue(
      new ApiError(
        409,
        {
          status: 409,
          error: 'Conflict',
          message: 'A dead-letter redrive is already running (2 of 5 message(s) moved)',
        },
        'failed',
      ),
    );

    renderWithProviders(<FailuresPage />);
    await user.click(await screen.findByRole('button', { name: 'Redrive queue' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/already running \(2 of 5/);
  });

  it('reports a failed redrive with its reason', async () => {
    vi.mocked(getQueueHealth).mockResolvedValue(queueHealth(2));
    vi.mocked(getDlqRedriveStatus).mockResolvedValue({
      status: 'FAILED',
      messagesMoved: 0,
      messagesToMove: 2,
      failureReason: 'destination queue does not exist',
    });

    renderWithProviders(<FailuresPage />);

    expect(await screen.findByText('failed')).toBeInTheDocument();
    expect(screen.getByText('destination queue does not exist')).toBeInTheDocument();
  });

  it('renders DLQ depth as unknown when SQS could not be read', async () => {
    vi.mocked(getQueueHealth).mockResolvedValue({ available: false, unavailableReason: 'gone' });
    renderWithProviders(<FailuresPage />);

    expect(await screen.findByText('unknown')).toBeInTheDocument();
    // Redrive would have nothing trustworthy to act on.
    expect(screen.getByRole('button', { name: 'Redrive queue' })).toBeDisabled();
  });

  it('does not claim nothing needs an operator while the DLQ has messages', async () => {
    vi.mocked(getFailures).mockResolvedValue([]);
    vi.mocked(getQueueHealth).mockResolvedValue(queueHealth(3));
    renderWithProviders(<FailuresPage />);

    // A dead-lettered message is operator work too, just the redrive workflow.
    expect(await screen.findByText(/still has messages waiting to be redriven/)).toBeInTheDocument();
  });

  it('says nothing needs an operator when both are clear', async () => {
    vi.mocked(getFailures).mockResolvedValue([]);
    renderWithProviders(<FailuresPage />);

    expect(await screen.findByText(/Nothing needs an operator right now/)).toBeInTheDocument();
  });

  it('counts failed orders separately from dead-lettered messages', async () => {
    vi.mocked(getFailures).mockResolvedValue([aFailure(), aFailure({ orderId: 'order-failed-2' })]);
    vi.mocked(getQueueHealth).mockResolvedValue(queueHealth(7));
    renderWithProviders(<FailuresPage />);

    // Two different situations with two different remedies; conflating the counts would
    // suggest redrive fixes a permanently failed order.
    const failedTile = (await screen.findByText('Failed orders')).closest('.stat');
    await waitFor(() =>
      expect(within(failedTile as HTMLElement).getByText('2')).toBeInTheDocument(),
    );
    const dlqTile = screen.getByText('Dead-letter queue').closest('.stat');
    await waitFor(() => expect(within(dlqTile as HTMLElement).getByText('7')).toBeInTheDocument());
  });
});
