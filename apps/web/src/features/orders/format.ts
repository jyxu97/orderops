const MONEY = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });
const DATE_TIME = new Intl.DateTimeFormat('en-US', {
  dateStyle: 'medium',
  timeStyle: 'medium',
});
const TIME = new Intl.DateTimeFormat('en-US', { timeStyle: 'medium' });

export function formatMoney(amount: number | undefined | null): string {
  return MONEY.format(amount ?? 0);
}

export function formatDateTime(iso: string): string {
  const date = new Date(iso);
  // An unparseable timestamp should show as-is rather than as "Invalid Date".
  return Number.isNaN(date.getTime()) ? iso : DATE_TIME.format(date);
}

export function formatTime(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : TIME.format(date);
}

/** Compact relative age, e.g. "12s ago", "4m ago". */
export function formatRelative(iso: string, now: number = Date.now()): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) {
    return iso;
  }
  const seconds = Math.max(0, Math.round((now - then) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.round(hours / 24)}d ago`;
}

/** Order IDs are UUIDs; a short prefix is enough to recognise one in a table. */
export function shortId(id: string): string {
  return id.length > 8 ? id.slice(0, 8) : id;
}
