import { describe, expect, it } from 'vitest';
import { ORDER_STATUSES } from '../../types';
import { FULFILLMENT_STEPS, statusLabel, statusPresentation } from './status';

describe('statusPresentation', () => {
  // If the backend gains a status and this table is not updated, the UI would render a blank
  // badge. Covering the enum makes that a failing test instead of a visual bug.
  it.each(ORDER_STATUSES)('has presentation for %s', (status) => {
    const presentation = statusPresentation(status);
    expect(presentation.label).not.toBe('');
    expect(presentation.description).not.toBe('');
  });

  it('maps granular backend names to customer-readable labels', () => {
    expect(statusLabel('INVENTORY_RESERVED')).toBe('Inventory reserved');
    expect(statusLabel('NEEDS_MANUAL_REVIEW')).toBe('Needs review');
  });

  it('gives terminal non-success states no progress position', () => {
    // A failed order is not "40% shipped", so it renders as an endpoint, not a partial track.
    expect(statusPresentation('FAILED').progress).toBe(-1);
    expect(statusPresentation('NEEDS_MANUAL_REVIEW').progress).toBe(-1);
    expect(statusPresentation('CANCELLED').progress).toBe(-1);
  });

  it('puts FULFILLED at the end of the track', () => {
    expect(statusPresentation('FULFILLED').progress).toBe(FULFILLMENT_STEPS);
  });

  it('advances progress monotonically along the happy path', () => {
    const happyPath = [
      'CREATED',
      'INVENTORY_RESERVED',
      'PAYMENT_PROCESSING',
      'PAYMENT_SUCCEEDED',
      'SHIPMENT_PROCESSING',
      'FULFILLED',
    ] as const;

    const progresses = happyPath.map((status) => statusPresentation(status).progress);
    expect(progresses).toEqual([0, 1, 2, 3, 4, 5]);
  });

  it('falls back to a readable label for an unknown status', () => {
    // @ts-expect-error deliberately passing a status the union does not contain
    expect(statusPresentation('SOME_NEW_STATUS').label).toBe('some new status');
  });
});
