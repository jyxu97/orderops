package com.orderops.worker;

import com.orderops.api.repository.AuditLogRepository;
import com.orderops.api.repository.DynamoDbLocalProcess;
import com.orderops.api.repository.DynamoDbTestBase;
import com.orderops.api.repository.InventoryRepository;
import com.orderops.api.repository.OrderRepository;
import com.orderops.shared.model.Inventory;
import com.orderops.shared.model.Order;
import com.orderops.shared.state.OrderStateMachine;
import com.orderops.shared.state.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link OrderFulfillmentService}.
 *
 * Uses a real DynamoDB Local instance (via {@link DynamoDbTestBase}) without SQS,
 * invoking {@link OrderFulfillmentService#fulfill} directly.
 */
class OrderFulfillmentServiceTest extends DynamoDbTestBase {

    private OrderRepository orderRepository;
    private AuditLogRepository auditLogRepository;
    private InventoryRepository inventoryRepository;
    private PaymentSimulator paymentSimulator;
    private ShipmentSimulator shipmentSimulator;
    private OrderFulfillmentService fulfillmentService;

    @BeforeEach
    void setUp() {
        orderRepository = new OrderRepository(dynamoDb);
        ReflectionTestUtils.setField(orderRepository, "tableName", "Orders");

        auditLogRepository = new AuditLogRepository(dynamoDb);
        ReflectionTestUtils.setField(auditLogRepository, "tableName", "OrderAuditLogs");

        inventoryRepository = new InventoryRepository(dynamoDb);
        ReflectionTestUtils.setField(inventoryRepository, "tableName", "Inventory");

        paymentSimulator = new PaymentSimulator();
        ReflectionTestUtils.setField(paymentSimulator, "failureMode", "NONE");
        ReflectionTestUtils.setField(paymentSimulator, "failureRate", 0.0);

        shipmentSimulator = new ShipmentSimulator();
        ReflectionTestUtils.setField(shipmentSimulator, "failureMode", "NONE");
        ReflectionTestUtils.setField(shipmentSimulator, "failureRate", 0.0);

        fulfillmentService = new OrderFulfillmentService(
            orderRepository, auditLogRepository,
            new OrderStateMachine(),
            paymentSimulator, shipmentSimulator);
    }

    @Test
    void fulfill_happyPath_orderReachesFulfilled() {
        Order order = seedOrder();

        fulfillmentService.fulfill(order.getOrderId());

        Order result = orderRepository.findById(order.getOrderId()).orElseThrow();
        assertEquals(OrderStatus.FULFILLED, result.getStatus());
        // version increments once per transition: 4 transitions total
        // INVENTORY_RESERVED → PAYMENT_PROCESSING → PAYMENT_SUCCEEDED → SHIPMENT_PROCESSING → FULFILLED
        assertEquals(order.getVersion() + 4, result.getVersion());
    }

    @Test
    void fulfill_alreadyFulfilled_isIdempotent() {
        Order order = seedOrder();
        fulfillmentService.fulfill(order.getOrderId());  // first run
        fulfillmentService.fulfill(order.getOrderId());  // second run — must be a no-op

        Order result = orderRepository.findById(order.getOrderId()).orElseThrow();
        assertEquals(OrderStatus.FULFILLED, result.getStatus());
    }

    @Test
    void fulfill_permanentPaymentFailure_orderNeedsManualReview() {
        ReflectionTestUtils.setField(paymentSimulator, "failureMode", "PERMANENT");
        Order order = seedOrder();

        fulfillmentService.fulfill(order.getOrderId());

        Order result = orderRepository.findById(order.getOrderId()).orElseThrow();
        assertEquals(OrderStatus.NEEDS_MANUAL_REVIEW, result.getStatus());
    }

    @Test
    void fulfill_permanentShipmentFailure_orderNeedsManualReview() {
        ReflectionTestUtils.setField(shipmentSimulator, "failureMode", "PERMANENT");
        Order order = seedOrder();

        fulfillmentService.fulfill(order.getOrderId());

        Order result = orderRepository.findById(order.getOrderId()).orElseThrow();
        assertEquals(OrderStatus.NEEDS_MANUAL_REVIEW, result.getStatus());
    }

    @Test
    void fulfill_transientPaymentFailure_throwsForSqsRetry() {
        ReflectionTestUtils.setField(paymentSimulator, "failureMode", "TRANSIENT");
        Order order = seedOrder();

        // Expect exception so SQS does not delete the message
        assertThrows(RuntimeException.class,
            () -> fulfillmentService.fulfill(order.getOrderId()));
    }

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------

    /** Seeds an order in INVENTORY_RESERVED state directly into DynamoDB. */
    private Order seedOrder() {
        String itemId = "test-item-" + UUID.randomUUID();
        inventoryRepository.save(Inventory.builder()
            .itemId(itemId)
            .totalQuantity(100)
            .availableQuantity(99)
            .reservedQuantity(1)
            .version(1L)
            .build());

        String now = Instant.now().toString();
        Order order = Order.builder()
            .orderId(UUID.randomUUID().toString())
            .customerId("test-customer")
            .items(List.of(Order.OrderItem.builder().itemId(itemId).quantity(1).build()))
            .status(OrderStatus.INVENTORY_RESERVED)
            .version(1L)
            .createdAt(now)
            .updatedAt(now)
            .build();
        orderRepository.save(order);
        return order;
    }
}