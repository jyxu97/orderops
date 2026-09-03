import { describe, expect, it } from 'vitest';
import { formatMoney, formatRelative, shortId } from './format';

describe('formatMoney', () => {
  it('formats an amount as USD', () => {
    expect(formatMoney(39.98)).toBe('$39.98');
  });

  it('renders a whole number with cents', () => {
    expect(formatMoney(249)).toBe('$249.00');
  });

  // Money arrives as a JSON number from Jackson, and an order with no priced items is 0 —
  // but a missing field should read as free rather than "$NaN".
  it.each([undefined, null])('treats %s as zero', (amount) => {
    expect(formatMoney(amount)).toBe('$0.00');
  });
});

describe('formatRelative', () => {
  const now = Date.parse('2026-09-02T12:00:00.000Z');

  it.each([
    ['2026-09-02T11:59:48.000Z', '12s ago'],
    ['2026-09-02T11:56:00.000Z', '4m ago'],
    ['2026-09-02T09:00:00.000Z', '3h ago'],
    ['2026-08-30T12:00:00.000Z', '3d ago'],
  ])('renders %s as %s', (iso, expected) => {
    expect(formatRelative(iso, now)).toBe(expected);
  });

  // Clock skew between the browser and the server can put a timestamp slightly in the future;
  // "-3s ago" would look like a bug.
  it('clamps a future timestamp to zero rather than going negative', () => {
    expect(formatRelative('2026-09-02T12:00:05.000Z', now)).toBe('0s ago');
  });

  it('passes an unparseable timestamp through unchanged', () => {
    expect(formatRelative('not-a-date', now)).toBe('not-a-date');
  });
});

describe('shortId', () => {
  it('truncates a UUID to a recognisable prefix', () => {
    expect(shortId('8bc053f4-40f8-491e-ab24-105e50107133')).toBe('8bc053f4');
  });

  it('leaves an already-short id alone', () => {
    expect(shortId('abc')).toBe('abc');
  });
});
