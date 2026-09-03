import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { ErrorBanner } from './ErrorBanner';

describe('ErrorBanner', () => {
  it('renders nothing without an error', () => {
    const { container } = render(<ErrorBanner error={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('shows the status and the backend message for an ApiError', () => {
    render(
      <ErrorBanner
        error={
          new ApiError(
            409,
            { status: 409, error: 'Conflict', message: 'Insufficient inventory' },
            'fallback',
          )
        }
      />,
    );

    expect(screen.getByRole('alert')).toHaveTextContent('409 Conflict');
    expect(screen.getByRole('alert')).toHaveTextContent('Insufficient inventory');
  });

  it('lists field errors so a form can point at what is wrong', () => {
    render(
      <ErrorBanner
        error={
          new ApiError(
            400,
            {
              status: 400,
              error: 'Bad Request',
              message: 'Request validation failed',
              fieldErrors: {
                customerId: 'customerId is required',
                'items[0].quantity': 'quantity must be at least 1',
              },
            },
            'fallback',
          )
        }
      />,
    );

    expect(screen.getByText('customerId')).toBeInTheDocument();
    expect(screen.getByText(/quantity must be at least 1/)).toBeInTheDocument();
  });

  it('falls back to a plain Error message', () => {
    render(<ErrorBanner error={new Error('network down')} />);

    expect(screen.getByRole('alert')).toHaveTextContent('network down');
  });

  it('handles a thrown non-Error without crashing', () => {
    render(<ErrorBanner error={'just a string'} />);

    expect(screen.getByRole('alert')).toHaveTextContent('Something went wrong.');
  });

  it('offers retry only when a handler is given', async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    const { rerender } = render(<ErrorBanner error={new Error('boom')} onRetry={onRetry} />);

    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalledTimes(1);

    rerender(<ErrorBanner error={new Error('boom')} />);
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  });
});
