package com.orderops.shared.exception;

import com.orderops.shared.state.OrderStatus;

/**
 * A status transition was rejected because the order was no longer in the state the caller
 * had read.
 *
 * <p>Carries the status DynamoDB actually found, which is what lets the caller tell apart the
 * two very different situations behind a lost race: the order reached a terminal state while
 * this attempt was in flight (nothing left to do), or it moved somewhere unexpected (a real
 * problem worth retrying and eventually dead-lettering).
 */
public class OrderStatusConflictException extends RuntimeException {

    private final OrderStatus expectedStatus;
    private final OrderStatus actualStatus;

    public OrderStatusConflictException(String orderId, OrderStatus expectedStatus, OrderStatus actualStatus) {
        super("Order %s is %s, not %s — another writer advanced it first"
            .formatted(orderId, actualStatus != null ? actualStatus : "gone", expectedStatus));
        this.expectedStatus = expectedStatus;
        this.actualStatus = actualStatus;
    }

    public OrderStatus getExpectedStatus() {
        return expectedStatus;
    }

    /** The status DynamoDB found, or null if the order no longer exists. */
    public OrderStatus getActualStatus() {
        return actualStatus;
    }
}
