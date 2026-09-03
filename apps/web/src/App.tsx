import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-router-dom';
import { RealtimeProvider } from './realtime/RealtimeContext';
import { router } from './routes/router';
import { ApiError } from './api/client';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Live updates arrive over WebSocket, so background polling would be redundant. Data is
      // still refetched on reconnect (see RealtimeProvider) and on window focus, which covers
      // the case where the socket was down and events were missed.
      refetchOnWindowFocus: true,
      staleTime: 10_000,
      retry: (failureCount, error) => {
        // A 4xx will not become a 2xx by asking again — only retry infrastructure failures.
        if (error instanceof ApiError && error.status < 500) {
          return false;
        }
        return failureCount < 2;
      },
    },
  },
});

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      {/* Inside the query provider: the realtime layer invalidates queries on reconnect. */}
      <RealtimeProvider>
        <RouterProvider router={router} />
      </RealtimeProvider>
    </QueryClientProvider>
  );
}
