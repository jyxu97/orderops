package com.orderops.shared.state;

import com.orderops.shared.exception.InvalidStateTransitionException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class OrderStateMachine {

    private static final Map<OrderStatus, Set<OrderStatus>> VALID_TRANSITIONS = Map.of(
        OrderStatus.CREATED,             Set.of(OrderStatus.INVENTORY_RESERVED, OrderStatus.CANCELLED),
        // A customer may cancel only before the worker starts charging. Once payment is in
        // flight the order is owned by the fulfillment pipeline.
        OrderStatus.INVENTORY_RESERVED,  Set.of(OrderStatus.PAYMENT_PROCESSING, OrderStatus.CANCELLED),
        OrderStatus.PAYMENT_PROCESSING,  Set.of(OrderStatus.PAYMENT_SUCCEEDED, OrderStatus.FAILED),
        OrderStatus.PAYMENT_SUCCEEDED,   Set.of(OrderStatus.SHIPMENT_PROCESSING),
        OrderStatus.SHIPMENT_PROCESSING, Set.of(OrderStatus.FULFILLED, OrderStatus.FAILED),
        OrderStatus.FAILED,              Set.of(OrderStatus.NEEDS_MANUAL_REVIEW),
        // Operator resolution for an order parked in manual review: releasing the reservation
        // returns the stock to the catalog.
        OrderStatus.NEEDS_MANUAL_REVIEW, Set.of(OrderStatus.CANCELLED)
    );

    /**
     * Validates that the transition from {@code current} to {@code next} is allowed.
     *
     * @throws InvalidStateTransitionException if the transition is not permitted
     */
    public void validateTransition(OrderStatus current, OrderStatus next) {
        Set<OrderStatus> allowed = VALID_TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(next)) {
            throw new InvalidStateTransitionException(current.name(), next.name());
        }
    }

    /** Whether an order in {@code current} may still be cancelled by a customer or operator. */
    public boolean isCancellable(OrderStatus current) {
        return VALID_TRANSITIONS.getOrDefault(current, Set.of()).contains(OrderStatus.CANCELLED);
    }
}
