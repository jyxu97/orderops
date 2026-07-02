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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
    private SimpleMeterRegistry meterRegistry;
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
        ReflectionTestUtils.setField(paymentSimulator, "transientFailsRemaining", new AtomicInteger(Integer.MAX_VALUE));

        shipmentSimulator = new ShipmentSimulator();
        ReflectionTestUtils.setField(shipmentSimulator, "failureMode", "NONE");
        ReflectionTestUtils.setField(shipmentSimulator, "failureRate", 0.0);
        ReflectionTestUtils.setField(shipmentSimulator, "transientFailsRemaining", new AtomicInteger(Integer.MAX_VALUE));

        meterRegistry = new SimpleMeterRegistry();

        fulfillmentService = new OrderFulfillmentService(
            orderRepository, auditLogRepository,
            new OrderStateMachine(),
            paymentSimulator, shipmentSimulator,
            meterRegistry);
    }

    @Test
    void fulfill_happyPath_orderReachesFulfilled() {
        Order order = seedOrder();

        fulfillmentService.fulfill(order.getOrderId());

        Order result = orderRepository.findById(order.getOrderId()).orElseThrow();
        assertEquals(OrderStatus.FULFILLED, result.getStatus());
        // 4 transitions: INVENTORY_RESERVED → PAYMENT_PROCESSING → PAYMENT_SUCCEEDED → SHIPMENT_PROCESSING → FULFILLED
        assertEquals(order.getVersion() + 4, result.getVersion());
        assertEquals(1.0, meterRegistry.counter("fulfillment.fulfilled").count());
    }

    @Test
    void fulfill_alreadyFulfilled_isIdempotent() {
        Order order = seedOrder();
        fulfillmentService.fulfill(order.getOrderId());  // first run
        fulfillmentService.fulfill(order.getOrderId());  // second run — must be a no-op

        Order result = orderRepository.findById(order.getOrderId()).orElseThrow();
        assertEquals(OrderStatus.FULFILLED, result.getStatus());
        assertEquals(1.0, meterRegistry.counter("fulfillment.skipped").count());
    }

    @Test
    void fulfill_permanentPaymentFailure_orderNeedsManualReview() {
        ReflectionTestUtils.setField(paymentSimulator, "failureMode", "PERMANENT");
        Order order = seedOrder();

        fulfillmentService.fulfill(order.getOrderId());

        Order result = orderRepository.findById(order.getOrderId()).orElseThrow();
        assertEquals(OrderStatus.NEEDS_MANUAL_REVIEW, result.getStatus());
        assertEquals(1.0, meterRegistry.counter("fulfillment.manual_review", "stage", "payment").count());
    }

    @Test
    void fulfill_permanentShipmentFailure_orderNeedsManualReview() {
        ReflectionTestUtils.setField(shipmentSimulator, "failureMode", "PERMANENT");
        Order order = seedOrder();

        fulfillmentService.fulfill(order.getOrderId());

        Order result = orderRepository.findById(order.getOrderId()).orElseThrow();
        assertEquals(OrderStatus.NEEDS_MANUAL_REVIEW, result.getStatus());
        assertEquals(1.0, meterRegistry.counter("fulfillment.manual_review", "stage", "shipment").count());
    }

    @Test
    void fulfill_transientPaymentFailure_throwsForSqsRetry() {
        ReflectionTestUtils.setField(paymentSimulator, "failureMode", "TRANSIENT");
        Order order = seedOrder();

        // Expect exception so SQS does not delete the message
        assertThrows(RuntimeException.class,
            () -> fulfillmentService.fulfill(order.getOrderId()));
        assertEquals(1.0, meterRegistry.counter("fulfillment.transient_failure").count());
    }

    @Test
    void fulfill_transientPaymentThenRecovery_orderFulfilled() {
        // Simulator fails once, then succeeds — simulates SQS redelivery after transient fault
        ReflectionTestUtils.setField(paymentSimulator, "failureMode", "TRANSIENT");
        ReflectionTestUtils.setField(paymentSimulator, "transientFailsRemaining", new AtomicInteger(1));
        Order order = seedOrder();

        // First call: transient failure, order left in PAYMENT_PROCESSING
        assertThrows(RuntimeException.class, () -> fulfillmentService.fulfill(order.getOrderId()));

        // Second call: resumes from PAYMENT_PROCESSING, payment succeeds, order reaches FULFILLED
        fulfillmentService.fulfill(order.getOrderId());

        Order result = orderRepository.findById(order.getOrderId()).orElseThrow();
        assertEquals(OrderStatus.FULFILLED, result.getStatus());
        assertEquals(1.0, meterRegistry.counter("fulfillment.transient_failure").count());
        assertEquals(1.0, meterRegistry.counter("fulfillment.fulfilled").count());
    }

    @Test
    void fulfill_transientShipmentThenRecovery_orderFulfilled() {
        // Simulator fails once at shipment stage, then succeeds on retry
        ReflectionTestUtils.setField(shipmentSimulator, "failureMode", "TRANSIENT");
        ReflectionTestUtils.setField(shipmentSimulator, "transientFailsRemaining", new AtomicInteger(1));
        Order order = seedOrder();

        // First call: payment succeeds, shipment fails transiently, order left in SHIPMENT_PROCESSING
        assertThrows(RuntimeException.class, () -> fulfillmentService.fulfill(order.getOrderId()));

        // Second call: resumes from SHIPMENT_PROCESSING, shipment succeeds
        fulfillmentService.fulfill(order.getOrderId());

        Order result = orderRepository.findById(order.getOrderId()).orElseThrow();
        assertEquals(OrderStatus.FULFILLED, result.getStatus());
        assertEquals(1.0, meterRegistry.counter("fulfillment.fulfilled").count());
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