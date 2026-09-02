package com.orderops.api.repository;

import com.orderops.shared.model.Order;
import com.orderops.shared.model.Page;
import com.orderops.shared.state.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Covers the two Orders GSIs and cursor-based pagination. */
class OrderQueryTest extends DynamoDbTestBase {

    private OrderRepository repository;

    @BeforeEach
    void setUp() {
        repository = new OrderRepository(dynamoDb);
        ReflectionTestUtils.setField(repository, "tableName", "Orders");
    }

    private Order save(String customerId, OrderStatus status, Instant timestamp) {
        String ts = timestamp.toString();
        Order order = Order.builder()
            .orderId(UUID.randomUUID().toString())
            .customerId(customerId)
            .items(List.of(Order.OrderItem.builder().itemId("item-1").quantity(2).build()))
            .status(status)
            .version(1L)
            .createdAt(ts)
            .updatedAt(ts)
            .build();
        repository.save(order);
        return order;
    }

    @Test
    void findByCustomerId_returnsOnlyThatCustomersOrders_newestFirst() {
        String customer = "cust-query-" + UUID.randomUUID();
        Instant base = Instant.now();
        Order oldest = save(customer, OrderStatus.INVENTORY_RESERVED, base.minus(2, ChronoUnit.HOURS));
        Order middle = save(customer, OrderStatus.INVENTORY_RESERVED, base.minus(1, ChronoUnit.HOURS));
        Order newest = save(customer, OrderStatus.INVENTORY_RESERVED, base);
        save("someone-else-" + UUID.randomUUID(), OrderStatus.INVENTORY_RESERVED, base);

        Page<Order> page = repository.findByCustomerId(customer, 25, null);

        assertEquals(
            List.of(newest.getOrderId(), middle.getOrderId(), oldest.getOrderId()),
            page.getItems().stream().map(Order::getOrderId).toList());
        assertNull(page.getNextCursor(), "a complete result set must not advertise another page");
    }

    @Test
    void findByCustomerId_paginatesWithCursor() {
        String customer = "cust-page-" + UUID.randomUUID();
        Instant base = Instant.now();
        for (int i = 0; i < 3; i++) {
            save(customer, OrderStatus.INVENTORY_RESERVED, base.minus(i, ChronoUnit.MINUTES));
        }

        Page<Order> first = repository.findByCustomerId(customer, 2, null);
        assertEquals(2, first.getItems().size());
        assertNotNull(first.getNextCursor());

        Page<Order> second = repository.findByCustomerId(customer, 2, first.getNextCursor());
        assertEquals(1, second.getItems().size());

        // The two pages must not overlap.
        assertTrue(first.getItems().stream().map(Order::getOrderId).noneMatch(
            id -> id.equals(second.getItems().get(0).getOrderId())));
    }

    @Test
    void findByStatus_filtersByStatus() {
        Instant base = Instant.now();
        Order failed = save("cust-status-" + UUID.randomUUID(), OrderStatus.FAILED, base);
        save("cust-status-" + UUID.randomUUID(), OrderStatus.FULFILLED, base);

        Page<Order> page = repository.findByStatus(OrderStatus.FAILED, 25, null);

        assertTrue(page.getItems().stream().anyMatch(o -> o.getOrderId().equals(failed.getOrderId())));
        assertTrue(page.getItems().stream().allMatch(o -> o.getStatus() == OrderStatus.FAILED));
    }

    @Test
    void findByCustomerId_unknownCustomer_returnsEmptyPage() {
        Page<Order> page = repository.findByCustomerId("nobody-" + UUID.randomUUID(), 25, null);
        assertTrue(page.getItems().isEmpty());
        assertNull(page.getNextCursor());
    }

    @Test
    void findByCustomerId_malformedCursor_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
            () -> repository.findByCustomerId("cust-1", 25, "not-a-valid-cursor!!!"));
    }

    @Test
    void countByStatus_countsOnlyThatStatus() {
        // CREATED is never persisted by the checkout path, so this status starts empty and the
        // assertion can be exact rather than relative.
        OrderRepository.StatusCount before = repository.countByStatus(OrderStatus.CREATED);

        Instant base = Instant.now();
        save("cust-count-" + UUID.randomUUID(), OrderStatus.CREATED, base);
        save("cust-count-" + UUID.randomUUID(), OrderStatus.CREATED, base.plusMillis(1));
        save("cust-count-" + UUID.randomUUID(), OrderStatus.FULFILLED, base);

        OrderRepository.StatusCount after = repository.countByStatus(OrderStatus.CREATED);

        assertEquals(before.count() + 2, after.count());
        assertFalse(after.capped(), "a small result set must not report as capped");
    }
}
