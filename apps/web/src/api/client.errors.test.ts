import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, request } from './client';

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('request', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('returns the parsed body on success', async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse(200, { orderId: 'o-1' }));

    await expect(request<{ orderId: string }>('/api/v1/orders/o-1')).resolves.toEqual({
      orderId: 'o-1',
    });
  });

  it('sends a JSON body and content type only when there is a body', async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse(201, {}));

    await request('/api/v1/orders', { method: 'POST', body: { customerId: 'c-1' } });

    const init = vi.mocked(fetch).mock.calls[0]?.[1];
    expect(init?.method).toBe('POST');
    expect(init?.body).toBe('{"customerId":"c-1"}');
    expect(init?.headers).toMatchObject({ 'Content-Type': 'application/json' });
  });

  it('omits the content type on a bodyless POST', async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse(200, {}));

    await request('/api/v1/orders/o-1/cancel', { method: 'POST' });

    expect(vi.mocked(fetch).mock.calls[0]?.[1]?.headers).not.toHaveProperty('Content-Type');
  });

  it('passes the idempotency key as a header', async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse(201, {}));

    await request('/api/v1/orders', { method: 'POST', body: {}, idempotencyKey: 'key-1' });

    expect(vi.mocked(fetch).mock.calls[0]?.[1]?.headers).toMatchObject({
      'Idempotency-Key': 'key-1',
    });
  });

  it('raises an ApiError carrying the backend message', async () => {
    vi.mocked(fetch).mockResolvedValue(
      jsonResponse(409, {
        status: 409,
        error: 'Conflict',
        message: 'Insufficient inventory for itemId=widget-a, requested=5',
      }),
    );

    // The server's message is the useful part — two different 409s need telling apart.
    await expect(request('/api/v1/orders', { method: 'POST', body: {} })).rejects.toThrow(
      /Insufficient inventory/,
    );
  });

  it('exposes field errors from a validation failure', async () => {
    vi.mocked(fetch).mockResolvedValue(
      jsonResponse(400, {
        status: 400,
        error: 'Bad Request',
        message: 'Request validation failed',
        fieldErrors: { customerId: 'customerId is required' },
      }),
    );

    const error = await request('/api/v1/orders', { method: 'POST', body: {} }).catch(
      (e: unknown) => e,
    );

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(400);
    expect((error as ApiError).fieldErrors).toEqual({ customerId: 'customerId is required' });
  });

  it('falls back to a generic message when the failure carries no JSON envelope', async () => {
    // A proxy 502 or a gateway timeout is HTML; the parse error must not mask the status.
    vi.mocked(fetch).mockResolvedValue(
      new Response('<html>502 Bad Gateway</html>', { status: 502 }),
    );

    const error = await request('/api/v1/orders/o-1').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(502);
    expect((error as ApiError).body).toBeNull();
    expect((error as Error).message).toContain('/api/v1/orders/o-1');
  });

  it('reports no field errors when the response had none', async () => {
    vi.mocked(fetch).mockResolvedValue(
      jsonResponse(404, { status: 404, error: 'Not Found', message: 'Order not found: nope' }),
    );

    const error = await request('/api/v1/orders/nope').catch((e: unknown) => e);

    expect((error as ApiError).fieldErrors).toEqual({});
  });

  it('does not try to parse a 204 body', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 204 }));

    await expect(request('/api/v1/whatever')).resolves.toBeUndefined();
  });
});
