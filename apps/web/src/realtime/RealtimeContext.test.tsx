import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { RealtimeProvider } from './RealtimeContext';
import { FakeRealtimeConnection } from '../test/FakeRealtimeConnection';

function renderProvider(connection: FakeRealtimeConnection, queryClient = new QueryClient()) {
  return render(
    <QueryClientProvider client={queryClient}>
      <RealtimeProvider connection={connection}>
        <span>child</span>
      </RealtimeProvider>
    </QueryClientProvider>,
  );
}

describe('RealtimeProvider', () => {
  it('activates the connection on mount', () => {
    const connection = new FakeRealtimeConnection();
    renderProvider(connection);

    expect(connection.activated).toBe(true);
  });

  it('invalidates every query after a reconnect', async () => {
    const connection = new FakeRealtimeConnection();
    const queryClient = new QueryClient();
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
    renderProvider(connection, queryClient);

    connection.simulateReconnect();

    // A client that was disconnected cannot know which events it missed, so anything cached
    // could be stale. Invalidating broadly is the only version that cannot be subtly wrong.
    await waitFor(() => expect(invalidate).toHaveBeenCalledTimes(1));
    expect(invalidate).toHaveBeenCalledWith();
  });

  it('does not invalidate without a reconnect', () => {
    const connection = new FakeRealtimeConnection();
    const queryClient = new QueryClient();
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
    renderProvider(connection, queryClient);

    expect(invalidate).not.toHaveBeenCalled();
  });

  it('leaves an injected connection open on unmount', async () => {
    // The connection belongs to whoever supplied it; tearing it down here would break a
    // second provider sharing the same socket.
    const connection = new FakeRealtimeConnection();
    const { unmount } = renderProvider(connection);

    unmount();

    expect(connection.activated).toBe(true);
  });
});
