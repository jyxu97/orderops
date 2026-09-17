package com.orderops.api.repository;

import com.orderops.shared.exception.OrderStatusConflictException;
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

    @Test
    void advanceStatus_movesTheOrderAndIncrementsVersion() {
        Order order = buildOrder();
        repository.save(order);

        repository.advanceStatus(order.getOrderId(), OrderStatus.INVENTORY_RESERVED,
            OrderStatus.PAYMENT_PROCESSING);

        Order found = repository.findById(order.getOrderId()).orElseThrow();
        assertEquals(OrderStatus.PAYMENT_PROCESSING, found.getStatus());
        // Incremented server-side, so it counts committed writes rather than echoing a value
        // the caller read.
        assertEquals(order.getVersion() + 1, found.getVersion());
    }

    @Test
    void advanceStatus_wrongExpectedStatus_isRejectedAndReportsWhatItFound() {
        Order order = buildOrder();   // INVENTORY_RESERVED
        repository.save(order);

        OrderStatusConflictException thrown = assertThrows(OrderStatusConflictException.class,
            () -> repository.advanceStatus(order.getOrderId(), OrderStatus.PAYMENT_SUCCEEDED,
                OrderStatus.SHIPMENT_PROCESSING));

        // The whole point of ReturnValuesOnConditionCheckFailure: a conflict that only said
        // "something changed" would leave the caller unable to tell a mid-flight cancel from a
        // genuine problem.
        assertEquals(OrderStatus.INVENTORY_RESERVED, thrown.getActualStatus());
        assertEquals(OrderStatus.PAYMENT_SUCCEEDED, thrown.getExpectedStatus());

        Order unchanged = repository.findById(order.getOrderId()).orElseThrow();
        assertEquals(OrderStatus.INVENTORY_RESERVED, unchanged.getStatus());
        assertEquals(order.getVersion(), unchanged.getVersion(), "a rejected write must not bump version");
    }

    @Test
    void advanceStatus_secondWriterLosesTheRace() {
        Order order = buildOrder();
        repository.save(order);

        repository.advanceStatus(order.getOrderId(), OrderStatus.INVENTORY_RESERVED,
            OrderStatus.PAYMENT_PROCESSING);

        // A duplicate delivery replaying the same transition from the same stale read.
        OrderStatusConflictException thrown = assertThrows(OrderStatusConflictException.class,
            () -> repository.advanceStatus(order.getOrderId(), OrderStatus.INVENTORY_RESERVED,
                OrderStatus.PAYMENT_PROCESSING));

        assertEquals(OrderStatus.PAYMENT_PROCESSING, thrown.getActualStatus());
    }

    @Test
    void advanceStatus_isUnaffectedByAnUnrelatedWrite() {
        Order order = buildOrder();
        repository.save(order);

        // Something else rewrites the record without touching status — the case a version
        // condition would have rejected, forcing a pointless redelivery.
        repository.save(Order.builder()
            .orderId(order.getOrderId())
            .customerId("renamed-customer")
            .items(order.getItems())
            .status(order.getStatus())
            .totalAmount(order.getTotalAmount())
            .version(order.getVersion() + 7)
            .createdAt(order.getCreatedAt())
            .updatedAt(Instant.now().toString())
            .build());

        assertDoesNotThrow(() -> repository.advanceStatus(order.getOrderId(),
            OrderStatus.INVENTORY_RESERVED, OrderStatus.PAYMENT_PROCESSING));
        assertEquals(OrderStatus.PAYMENT_PROCESSING,
            repository.findById(order.getOrderId()).orElseThrow().getStatus());
    }
}
