import type { OrderStatus } from '../../types';

/**
 * Presentation for the backend's nine order statuses.
 *
 * The backend keeps granular names (`PAYMENT_PROCESSING`, `NEEDS_MANUAL_REVIEW`) because an
 * operator needs to know *which* stage failed. Those names are not what a customer should read,
 * so the mapping to human labels lives here — one table rather than string handling scattered
 * across components.
 *
 * `tone` drives colour. `progress` is the step index used by the order timeline; terminal
 * states that are not "done" carry -1 so they render as an endpoint rather than a step.
 */
interface StatusPresentation {
  label: string;
  tone: 'pending' | 'active' | 'success' | 'danger' | 'neutral';
  progress: number;
  description: string;
}

export const FULFILLMENT_STEPS = 5;

const PRESENTATION: Record<OrderStatus, StatusPresentation> = {
  CREATED: {
    label: 'Created',
    tone: 'pending',
    progress: 0,
    description: 'Order received, inventory not yet held.',
  },
  INVENTORY_RESERVED: {
    label: 'Inventory reserved',
    tone: 'active',
    progress: 1,
    description: 'Stock is held for this order and waiting for fulfillment to pick it up.',
  },
  PAYMENT_PROCESSING: {
    label: 'Processing payment',
    tone: 'active',
    progress: 2,
    description: 'Payment is being authorized.',
  },
  PAYMENT_SUCCEEDED: {
    label: 'Payment authorized',
    tone: 'active',
    progress: 3,
    description: 'Payment went through; preparing shipment.',
  },
  SHIPMENT_PROCESSING: {
    label: 'Preparing shipment',
    tone: 'active',
    progress: 4,
    description: 'Shipment is being dispatched.',
  },
  FULFILLED: {
    label: 'Fulfilled',
    tone: 'success',
    progress: 5,
    description: 'Order completed.',
  },
  FAILED: {
    label: 'Failed',
    tone: 'danger',
    progress: -1,
    description: 'Fulfillment failed and is being routed for review.',
  },
  NEEDS_MANUAL_REVIEW: {
    label: 'Needs review',
    tone: 'danger',
    progress: -1,
    description: 'Fulfillment failed permanently and needs an operator.',
  },
  CANCELLED: {
    label: 'Cancelled',
    tone: 'neutral',
    progress: -1,
    description: 'Order cancelled; reserved stock returned to the catalog.',
  },
};

export function statusPresentation(status: OrderStatus): StatusPresentation {
  return (
    PRESENTATION[status] ?? {
      // Defensive: a status added to the backend but not yet here should render readably
      // rather than blank.
      label: String(status).replaceAll('_', ' ').toLowerCase(),
      tone: 'neutral' as const,
      progress: -1,
      description: '',
    }
  );
}

export function statusLabel(status: OrderStatus): string {
  return statusPresentation(status).label;
}

/** Statuses an operator considers a failure. Mirrors `OperationsService.FAILURE_STATUSES`. */
export const FAILURE_STATUSES: OrderStatus[] = ['FAILED', 'NEEDS_MANUAL_REVIEW'];
