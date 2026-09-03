import { describe, expect, it } from 'vitest';
import { queryString } from './client';

describe('queryString', () => {
  it('builds a query string from present values', () => {
    expect(queryString({ customerId: 'customer-1', limit: 25 })).toBe('?customerId=customer-1&limit=25');
  });

  // The list endpoints take optional cursor/limit params; sending `cursor=undefined` would be
  // rejected by the backend as a malformed cursor.
  it('omits undefined, null and empty values', () => {
    expect(queryString({ status: 'FAILED', cursor: undefined, customerId: null, q: '' })).toBe(
      '?status=FAILED',
    );
  });

  it('returns an empty string when nothing is set', () => {
    expect(queryString({ cursor: undefined })).toBe('');
  });

  it('encodes values that need it', () => {
    expect(queryString({ customerId: 'a b&c' })).toBe('?customerId=a+b%26c');
  });
});
