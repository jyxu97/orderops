package com.orderops.realtime;

import com.orderops.shared.event.OrderStatusEvent;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The STOMP destinations an order event fans out to, and the only destinations a client is
 * allowed to subscribe to.
 *
 * <p>Three topics rather than one so a client subscribes to exactly what it renders: an order
 * detail page wants one order, an order list wants one customer, the dashboard wants everything.
 * Without the narrower topics every client would have to receive every event and filter, which
 * scales badly and leaks other customers' order IDs to anyone connected.
 */
public final class RealtimeTopics {

    /** One specific order — the order detail page. */
    public static final String ORDER_PREFIX = "/topic/orders/";
    /** All of one customer's orders — the order list page. */
    public static final String CUSTOMER_PREFIX = "/topic/customers/";
    private static final String CUSTOMER_SUFFIX = "/orders";
    /** Every order event — the operations dashboard. */
    public static final String OPS_ORDERS = "/topic/ops/orders";

    /**
     * Destinations a SUBSCRIBE frame may name.
     *
     * <p>An allowlist, not a denylist: an unrecognised destination is rejected. The ID segments
     * exclude {@code /} so a destination cannot be widened by appending path segments, and
     * exclude {@code *} so a client cannot subscribe to a wildcard.
     */
    private static final List<Pattern> ALLOWED_SUBSCRIPTIONS = List.of(
        Pattern.compile("^/topic/orders/[^/*]+$"),
        Pattern.compile("^/topic/customers/[^/*]+/orders$"),
        Pattern.compile("^" + Pattern.quote(OPS_ORDERS) + "$")
    );

    private RealtimeTopics() {}

    public static String order(String orderId) {
        return ORDER_PREFIX + orderId;
    }

    public static String customerOrders(String customerId) {
        return CUSTOMER_PREFIX + customerId + CUSTOMER_SUFFIX;
    }

    /** Every destination {@code event} should be delivered to. */
    public static List<String> destinationsFor(OrderStatusEvent event) {
        if (event.getCustomerId() == null || event.getCustomerId().isBlank()) {
            return List.of(order(event.getOrderId()), OPS_ORDERS);
        }
        return List.of(order(event.getOrderId()), customerOrders(event.getCustomerId()), OPS_ORDERS);
    }

    public static boolean isSubscribable(String destination) {
        if (destination == null) {
            return false;
        }
        return ALLOWED_SUBSCRIPTIONS.stream().anyMatch(p -> p.matcher(destination).matches());
    }
}
