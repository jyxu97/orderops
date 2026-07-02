package com.orderops.shared.state;

import com.orderops.shared.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderStateMachineTest {

    private final OrderStateMachine stateMachine = new OrderStateMachine();

    @Test
    void validTransition_createdToInventoryReserved() {
        assertDoesNotThrow(() ->
            stateMachine.validateTransition(OrderStatus.CREATED, OrderStatus.INVENTORY_RESERVED));
    }

    @Test
    void validTransition_inventoryReservedToPaymentProcessing() {
        assertDoesNotThrow(() ->
            stateMachine.validateTransition(OrderStatus.INVENTORY_RESERVED, OrderStatus.PAYMENT_PROCESSING));
    }

    @Test
    void validTransition_paymentProcessingToPaymentSucceeded() {
        assertDoesNotThrow(() ->
            stateMachine.validateTransition(OrderStatus.PAYMENT_PROCESSING, OrderStatus.PAYMENT_SUCCEEDED));
    }

    @Test
    void validTransition_paymentProcessingToFailed() {
        assertDoesNotThrow(() ->
            stateMachine.validateTransition(OrderStatus.PAYMENT_PROCESSING, OrderStatus.FAILED));
    }

    @Test
    void validTransition_paymentSucceededToShipmentProcessing() {
        assertDoesNotThrow(() ->
            stateMachine.validateTransition(OrderStatus.PAYMENT_SUCCEEDED, OrderStatus.SHIPMENT_PROCESSING));
    }

    @Test
    void validTransition_shipmentProcessingToFulfilled() {
        assertDoesNotThrow(() ->
            stateMachine.validateTransition(OrderStatus.SHIPMENT_PROCESSING, OrderStatus.FULFILLED));
    }

    @Test
    void validTransition_shipmentProcessingToFailed() {
        assertDoesNotThrow(() ->
            stateMachine.validateTransition(OrderStatus.SHIPMENT_PROCESSING, OrderStatus.FAILED));
    }

    @Test
    void validTransition_failedToNeedsManualReview() {
        assertDoesNotThrow(() ->
            stateMachine.validateTransition(OrderStatus.FAILED, OrderStatus.NEEDS_MANUAL_REVIEW));
    }

    @Test
    void invalidTransition_createdToFulfilled() {
        assertThrows(InvalidStateTransitionException.class, () ->
            stateMachine.validateTransition(OrderStatus.CREATED, OrderStatus.FULFILLED));
    }

    @Test
    void invalidTransition_fulfilledToCreated() {
        assertThrows(InvalidStateTransitionException.class, () ->
            stateMachine.validateTransition(OrderStatus.FULFILLED, OrderStatus.CREATED));
    }

    @Test
    void invalidTransition_inventoryReservedToFulfilled() {
        assertThrows(InvalidStateTransitionException.class, () ->
            stateMachine.validateTransition(OrderStatus.INVENTORY_RESERVED, OrderStatus.FULFILLED));
    }

    @Test
    void invalidTransition_createdToPaymentProcessing() {
        assertThrows(InvalidStateTransitionException.class, () ->
            stateMachine.validateTransition(OrderStatus.CREATED, OrderStatus.PAYMENT_PROCESSING));
    }

    @Test
    void invalidTransition_needsManualReviewHasNoValidTargets() {
        // NEEDS_MANUAL_REVIEW is a terminal state — no transitions allowed
        assertThrows(InvalidStateTransitionException.class, () ->
            stateMachine.validateTransition(OrderStatus.NEEDS_MANUAL_REVIEW, OrderStatus.CREATED));
    }
}
