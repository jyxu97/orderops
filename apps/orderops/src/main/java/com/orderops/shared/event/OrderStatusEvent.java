package com.orderops.shared.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.orderops.shared.state.OrderStatus;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;

/**
 * A committed order state change, broadcast to WebSocket subscribers.
 *
 * <p>Events are delivery hints, not the source of truth. A client may miss one — a dropped
 * connection, a Redis blip — so the UI refetches the order over REST after reconnecting rather
 * than assuming its event stream was gap-free. DynamoDB remains authoritative.
 *
 * <p>Kept deliberately small: enough for the UI to patch a row or decide to refetch, and no
 * more. Full order details come from {@code GET /api/v1/orders/{orderId}}.
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrderStatusEvent {

    String type;
    String orderId;
    String customerId;
    /** Null for the first event of an order's life, which has no prior status. */
    String previousStatus;
    String status;
    String reason;
    String occurredAt;

    /**
     * Wall-clock time the state change was committed to DynamoDB, in epoch milliseconds.
     *
     * <p>Present so a client can measure end-to-end delivery latency (commit → received) with
     * a single subtraction. Only meaningful when publisher and subscriber share a clock, which
     * is why the latency benchmark runs both on one host.
     */
    long committedAtEpochMilli;

    public static OrderStatusEvent statusChanged(
        String orderId, String customerId, OrderStatus previousStatus, OrderStatus status, String reason) {

        Instant now = Instant.now();
        return OrderStatusEvent.builder()
            .type("ORDER_STATUS_CHANGED")
            .orderId(orderId)
            .customerId(customerId)
            .previousStatus(previousStatus != null ? previousStatus.name() : null)
            .status(status.name())
            .reason(reason)
            .occurredAt(now.toString())
            .committedAtEpochMilli(now.toEpochMilli())
            .build();
    }
}
