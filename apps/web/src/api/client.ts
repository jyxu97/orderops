import type { ApiErrorBody } from '../types';

/**
 * Empty by default, so requests go to the current origin — which is what both local
 * development (Vite proxies `/api`) and a CloudFront-fronted deployment want. Set
 * `VITE_API_BASE_URL` only for the split-origin setup where the API lives on its own hostname.
 */
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '';

/**
 * A non-2xx response, carrying the backend's parsed `ErrorResponse` when there was one.
 *
 * Callers get the server's message rather than a generic "request failed", which matters here:
 * "Insufficient inventory for itemId=widget-a" and "Idempotency-Key was already used with a
 * different request body" are both 409s that a user needs told apart.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly body: ApiErrorBody | null;

  constructor(status: number, body: ApiErrorBody | null, fallbackMessage: string) {
    super(body?.message ?? fallbackMessage);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
  }

  /** Validation messages keyed by field, for rendering next to form inputs. */
  get fieldErrors(): Record<string, string> {
    return this.body?.fieldErrors ?? {};
  }
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH';
  body?: unknown;
  /** Sent as the `Idempotency-Key` header. */
  idempotencyKey?: string;
  signal?: AbortSignal;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, idempotencyKey, signal } = options;

  const headers: Record<string, string> = {};
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (idempotencyKey) {
    headers['Idempotency-Key'] = idempotencyKey;
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    method,
    headers,
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
    ...(signal ? { signal } : {}),
  });

  if (!response.ok) {
    throw new ApiError(response.status, await readErrorBody(response), `${method} ${path} failed`);
  }

  // 204 and 205 carry no body; parsing one would throw.
  if (response.status === 204 || response.status === 205) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

/**
 * A failing response may not carry the JSON envelope — a proxy 502 or a gateway timeout is
 * plain HTML. Returning null in that case lets the caller fall back to a generic message
 * instead of the parse error masking the real status.
 */
async function readErrorBody(response: Response): Promise<ApiErrorBody | null> {
  try {
    return (await response.json()) as ApiErrorBody;
  } catch {
    return null;
  }
}

/** Builds a query string, omitting params that are undefined, null or empty. */
export function queryString(params: Record<string, string | number | undefined | null>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, String(value));
    }
  }
  const encoded = search.toString();
  return encoded ? `?${encoded}` : '';
}
