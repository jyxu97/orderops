package com.orderops.shared.state;

import com.orderops.shared.exception.InvalidStateTransitionException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class OrderStateMachine {

    private static final Map<OrderStatus, Set<OrderStatus>> VALID_TRANSITIONS = Map.of(
        OrderStatus.CREATED,             Set.of(OrderStatus.INVENTORY_RESERVED),
        OrderStatus.INVENTORY_RESERVED,  Set.of(OrderStatus.PAYMENT_PROCESSING),
        OrderStatus.PAYMENT_PROCESSING,  Set.of(OrderStatus.PAYMENT_SUCCEEDED, OrderStatus.FAILED),
        OrderStatus.PAYMENT_SUCCEEDED,   Set.of(OrderStatus.SHIPMENT_PROCESSING),
        OrderStatus.SHIPMENT_PROCESSING, Set.of(OrderStatus.FULFILLED, OrderStatus.FAILED),
        OrderStatus.FAILED,              Set.of(OrderStatus.NEEDS_MANUAL_REVIEW)
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
}
