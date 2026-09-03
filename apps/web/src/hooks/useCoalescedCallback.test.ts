import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useCoalescedCallback } from './useCoalescedCallback';

describe('useCoalescedCallback', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('fires once for a burst of calls', () => {
    const callback = vi.fn();
    const { result } = renderHook(() => useCoalescedCallback(callback, 1000));

    // The ops topic delivers ~5 events per order; a checkout burst is hundreds. Each one must
    // not become its own refetch.
    act(() => {
      for (let i = 0; i < 200; i++) {
        result.current();
      }
    });

    expect(callback).not.toHaveBeenCalled();

    act(() => {
      vi.advanceTimersByTime(1000);
    });

    expect(callback).toHaveBeenCalledTimes(1);
  });

  it('fires on the trailing edge, not the leading one', () => {
    const callback = vi.fn();
    const { result } = renderHook(() => useCoalescedCallback(callback, 500));

    act(() => {
      result.current();
    });
    // Reading state mid-burst would return a value that is stale before it lands.
    expect(callback).not.toHaveBeenCalled();

    act(() => {
      vi.advanceTimersByTime(499);
    });
    expect(callback).not.toHaveBeenCalled();

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(callback).toHaveBeenCalledTimes(1);
  });

  it('allows a new fire in the next window', () => {
    const callback = vi.fn();
    const { result } = renderHook(() => useCoalescedCallback(callback, 100));

    act(() => {
      result.current();
      vi.advanceTimersByTime(100);
    });
    act(() => {
      result.current();
      vi.advanceTimersByTime(100);
    });

    expect(callback).toHaveBeenCalledTimes(2);
  });

  it('uses the latest callback without restarting the window', () => {
    const first = vi.fn();
    const second = vi.fn();
    const { result, rerender } = renderHook(({ cb }) => useCoalescedCallback(cb, 100), {
      initialProps: { cb: first },
    });

    act(() => {
      result.current();
    });
    // An inline arrow handler is a new function every render; holding it in a ref means a
    // re-render mid-window does not drop the pending fire or re-subscribe.
    rerender({ cb: second });
    act(() => {
      vi.advanceTimersByTime(100);
    });

    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(1);
  });

  it('does not fire after unmount', () => {
    const callback = vi.fn();
    const { result, unmount } = renderHook(() => useCoalescedCallback(callback, 100));

    act(() => {
      result.current();
    });
    unmount();
    act(() => {
      vi.advanceTimersByTime(500);
    });

    // A pending fire touching an unmounted component's queries would be a leak.
    expect(callback).not.toHaveBeenCalled();
  });
});
