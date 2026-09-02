package com.orderops.api.repository;

import com.orderops.shared.model.OrderAuditLog;
import com.orderops.shared.state.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AuditLogRepositoryTest extends DynamoDbTestBase {

    private AuditLogRepository repository;

    @BeforeEach
    void setUp() {
        repository = new AuditLogRepository(dynamoDb);
        ReflectionTestUtils.setField(repository, "tableName", "OrderAuditLogs");
    }

    private void record(String orderId, OrderStatus from, OrderStatus to, Instant at, String reason) {
        repository.save(OrderAuditLog.builder()
            .orderId(orderId)
            .timestamp(at.toString())
            .fromStatus(from.name())
            .toStatus(to.name())
            .reason(reason)
            .build());
    }

    @Test
    void findByOrderId_returnsHistoryOldestFirst() {
        String orderId = "audit-" + UUID.randomUUID();
        Instant base = Instant.now();
        record(orderId, OrderStatus.CREATED, OrderStatus.INVENTORY_RESERVED, base, "Order created");
        record(orderId, OrderStatus.INVENTORY_RESERVED, OrderStatus.PAYMENT_PROCESSING,
            base.plus(1, ChronoUnit.SECONDS), "Processing payment");
        record(orderId, OrderStatus.PAYMENT_PROCESSING, OrderStatus.FAILED,
            base.plus(2, ChronoUnit.SECONDS), "Payment declined");

        List<OrderAuditLog> history = repository.findByOrderId(orderId);

        assertEquals(3, history.size());
        assertEquals("CREATED", history.get(0).getFromStatus());
        assertEquals("FAILED", history.get(2).getToStatus());
        assertEquals("Payment declined", history.get(2).getReason());
    }

    @Test
    void findByOrderId_isScopedToOneOrder() {
        String mine = "audit-" + UUID.randomUUID();
        String theirs = "audit-" + UUID.randomUUID();
        Instant base = Instant.now();
        record(mine, OrderStatus.CREATED, OrderStatus.INVENTORY_RESERVED, base, "mine");
        record(theirs, OrderStatus.CREATED, OrderStatus.INVENTORY_RESERVED, base, "theirs");

        List<OrderAuditLog> history = repository.findByOrderId(mine);

        assertEquals(1, history.size());
        assertEquals("mine", history.get(0).getReason());
    }

    @Test
    void findLatestByOrderId_returnsTheMostRecentEntry() {
        String orderId = "audit-" + UUID.randomUUID();
        Instant base = Instant.now();
        record(orderId, OrderStatus.CREATED, OrderStatus.INVENTORY_RESERVED, base, "first");
        record(orderId, OrderStatus.PAYMENT_PROCESSING, OrderStatus.FAILED,
            base.plus(5, ChronoUnit.SECONDS), "last");

        OrderAuditLog latest = repository.findLatestByOrderId(orderId).orElseThrow();

        assertEquals("last", latest.getReason());
        assertEquals("FAILED", latest.getToStatus());
    }

    @Test
    void findLatestByOrderId_orderWithNoHistory_returnsEmpty() {
        assertTrue(repository.findLatestByOrderId("audit-none-" + UUID.randomUUID()).isEmpty());
    }

    @Test
    void save_entryWithoutReason_roundTripsWithNullReason() {
        String orderId = "audit-" + UUID.randomUUID();
        repository.save(OrderAuditLog.builder()
            .orderId(orderId)
            .timestamp(Instant.now().toString())
            .fromStatus(OrderStatus.CREATED.name())
            .toStatus(OrderStatus.INVENTORY_RESERVED.name())
            .build());

        assertNull(repository.findLatestByOrderId(orderId).orElseThrow().getReason());
    }
}
