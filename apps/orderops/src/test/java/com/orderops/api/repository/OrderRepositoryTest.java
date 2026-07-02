package com.orderops.api.repository;

import com.orderops.shared.model.Order;
import com.orderops.shared.state.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OrderRepositoryTest extends DynamoDbTestBase {

    private OrderRepository repository;

    @BeforeEach
    void setUp() {
        repository = new OrderRepository(dynamoDb);
        ReflectionTestUtils.setField(repository, "tableName", "Orders");
    }

    private Order buildOrder() {
        String now = Instant.now().toString();
        return Order.builder()
            .orderId(UUID.randomUUID().toString())
            .customerId("cust-1")
            .items(List.of(Order.OrderItem.builder().itemId("item-1").quantity(2).build()))
            .status(OrderStatus.INVENTORY_RESERVED)
            .version(1L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    }

    @Test
    void saveAndFindById_roundTrip() {
        Order order = buildOrder();
        repository.save(order);

        Optional<Order> found = repository.findById(order.getOrderId());
        assertTrue(found.isPresent());
        assertEquals(order.getOrderId(), found.get().getOrderId());
        assertEquals(OrderStatus.INVENTORY_RESERVED, found.get().getStatus());
        assertEquals(1, found.get().getItems().size());
        assertEquals("item-1", found.get().getItems().get(0).getItemId());
    }

    @Test
    void findById_notFound_returnsEmpty() {
        Optional<Order> result = repository.findById("order-does-not-exist-" + UUID.randomUUID());
        assertTrue(result.isEmpty());
    }

    @Test
    void save_overwritesExistingOrder() {
        Order order = buildOrder();
        repository.save(order);

        Order updated = Order.builder()
            .orderId(order.getOrderId())
            .customerId(order.getCustomerId())
            .items(order.getItems())
            .status(OrderStatus.PAYMENT_PROCESSING)
            .version(2L)
            .createdAt(order.getCreatedAt())
            .updatedAt(Instant.now().toString())
            .build();
        repository.save(updated);

        Order found = repository.findById(order.getOrderId()).orElseThrow();
        assertEquals(OrderStatus.PAYMENT_PROCESSING, found.getStatus());
        assertEquals(2L, found.getVersion());
    }
}
