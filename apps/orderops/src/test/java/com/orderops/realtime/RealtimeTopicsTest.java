package com.orderops.realtime;

import com.orderops.shared.event.OrderStatusEvent;
import com.orderops.shared.state.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RealtimeTopicsTest {

    private static OrderStatusEvent event(String orderId, String customerId) {
        return OrderStatusEvent.statusChanged(
            orderId, customerId, OrderStatus.INVENTORY_RESERVED, OrderStatus.PAYMENT_PROCESSING, "test");
    }

    @Test
    void destinationsFor_fansOutToOrderCustomerAndOps() {
        List<String> destinations = RealtimeTopics.destinationsFor(event("o-1", "c-1"));

        assertEquals(
            List.of("/topic/orders/o-1", "/topic/customers/c-1/orders", "/topic/ops/orders"),
            destinations);
    }

    @Test
    void destinationsFor_eventWithoutCustomer_skipsTheCustomerTopic() {
        // A blank customerId would otherwise produce "/topic/customers//orders", a destination
        // no client can subscribe to and every customer-scoped event would land in.
        List<String> destinations = RealtimeTopics.destinationsFor(event("o-1", null));

        assertEquals(List.of("/topic/orders/o-1", "/topic/ops/orders"), destinations);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/topic/orders/order-123",
        "/topic/customers/customer-1/orders",
        "/topic/ops/orders"
    })
    void isSubscribable_allowsTheThreeSupportedShapes(String destination) {
        assertTrue(RealtimeTopics.isSubscribable(destination));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/topic/**",                       // wildcard subscription to everything
        "/topic/orders/*",                 // wildcard across all orders
        "/topic/orders",                   // the prefix itself, no order named
        "/topic/orders/a/b",               // widened by an extra path segment
        "/topic/customers/c-1",            // missing the /orders suffix
        "/topic/customers/c-1/orders/x",   // widened past the suffix
        "/topic/ops",
        "/topic/ops/orders/extra",
        "/queue/orders/o-1",               // a different broker prefix
        "/topic/",
        ""
    })
    void isSubscribable_rejectsAnythingElse(String destination) {
        assertFalse(RealtimeTopics.isSubscribable(destination), destination + " must be rejected");
    }

    @Test
    void isSubscribable_nullDestination_isRejected() {
        assertFalse(RealtimeTopics.isSubscribable(null));
    }
}
