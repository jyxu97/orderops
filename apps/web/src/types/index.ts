/**
 * Mirrors the backend DTOs in `com.orderops.api.dto` and the STOMP event payload in
 * `com.orderops.shared.event`. Kept hand-written rather than generated: the API surface is
 * small and stable, and a generator would be more machinery than the shapes justify.
 *
 * Money arrives as a JSON number (Java `BigDecimal` serialized by Jackson), so it is `number`
 * here. Formatting for display goes through `formatMoney` so precision handling lives in one
 * place rather than at every call site.
 */

/** Every status the backend's `OrderStatus` enum can hold. */
export const ORDER_STATUSES = [
  'CREATED',
  'INVENTORY_RESERVED',
  'PAYMENT_PROCESSING',
  'PAYMENT_SUCCEEDED',
  'SHIPMENT_PROCESSING',
  'FULFILLED',
  'FAILED',
  'NEEDS_MANUAL_REVIEW',
  'CANCELLED',
] as const;

export type OrderStatus = (typeof ORDER_STATUSES)[number];

export interface OrderItem {
  itemId: string;
  quantity: number;
  unitPrice: number;
  lineTotal: number;
}

export interface Order {
  orderId: string;
  customerId: string;
  items: OrderItem[];
  status: OrderStatus;
  totalAmount: number;
  /** Derived server-side from the state machine, so the UI never hardcodes its own copy. */
  cancellable: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface OrderSummary {
  orderId: string;
  customerId: string;
  status: OrderStatus;
  itemCount: number;
  totalQuantity: number;
  totalAmount: number;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreateOrderRequest {
  customerId: string;
  items: { itemId: string; quantity: number }[];
}

export interface CreateOrderResponse {
  orderId: string;
  status: OrderStatus;
  totalAmount: number;
  createdAt: string;
  /** True when the server answered from an idempotency record instead of creating an order. */
  replayed: boolean;
}

export interface Page<T> {
  items: T[];
  /** Absent when there are no further results. */
  nextCursor?: string;
}

export interface InventoryItem {
  itemId: string;
  itemName: string | null;
  unitPrice: number;
  totalQuantity: number;
  availableQuantity: number;
  reservedQuantity: number;
  version: number;
}

export interface OrderAuditEntry {
  timestamp: string;
  fromStatus: string;
  toStatus: string;
  reason?: string;
}

export interface QueueStats {
  queueName: string;
  visibleMessages: number;
  inFlightMessages: number;
  delayedMessages: number;
}

export interface QueueHealth {
  available: boolean;
  unavailableReason?: string;
  queue?: QueueStats;
  deadLetterQueue?: QueueStats;
  /** Absent when `available` is false — a missing reading is never a healthy one. */
  healthy?: boolean;
  warnings?: string[];
}

export interface FailedOrder {
  orderId: string;
  customerId: string;
  status: OrderStatus;
  totalAmount: number;
  lastFailureReason?: string;
  failedAt: string;
  cancellable: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface OpsOverview {
  statusCounts: Record<OrderStatus, number>;
  /** True when a status count stopped at its page cap, making the totals lower bounds. */
  countsCapped: boolean;
  recentOrders: OrderSummary[];
  queueHealth: QueueHealth;
  generatedAt: string;
}

/** The `ErrorResponse` shape returned by the backend's `GlobalExceptionHandler`. */
export interface ApiErrorBody {
  status: number;
  error: string;
  message: string;
  /** Field name → validation message. Present only on 400 validation failures. */
  fieldErrors?: Record<string, string>;
}

/** Payload broadcast on the STOMP topics. */
export interface OrderStatusEvent {
  type: 'ORDER_STATUS_CHANGED';
  orderId: string;
  customerId?: string;
  previousStatus?: OrderStatus;
  status: OrderStatus;
  reason?: string;
  occurredAt: string;
  /** Commit time in epoch ms, used by the latency benchmark. */
  committedAtEpochMilli: number;
}
