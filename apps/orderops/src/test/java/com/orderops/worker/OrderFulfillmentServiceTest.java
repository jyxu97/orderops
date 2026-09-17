package com.orderops.worker;

import com.orderops.api.repository.AuditLogRepository;
import com.orderops.api.repository.DynamoDbLocalProcess;
import com.orderops.api.repository.DynamoDbTestBase;
import com.orderops.api.repository.InventoryRepository;
import com.orderops.api.repository.OrderRepository;
import com.orderops.realtime.OrderEventPublisher;
import com.orderops.shared.event.OrderStatusEvent;
import com.orderops.shared.model.Inventory;
import com.orderops.shared.model.Order;
import com.orderops.shared.state.OrderStateMachine;
import com.orderops.shared.state.OrderStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
    private OrderEventPublisher eventPublisher;
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
        eventPublisher = Mockito.mock(OrderEventPublisher.class);

        fulfillmentService = new OrderFulfillmentService(
            orderRepository, auditLogRepository, inventoryRepository, dynamoDb,
            new OrderStateMachine(),
            paymentSimulator, shipmentSimulator,
            meterRegistry, eventPublisher);
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

    @Test
    void fulfill_cancelledOrder_isSkipped() {
        Order order = seedOrder();
        // A customer cancelling while the fulfillment message is still in flight has already
        // released the reservation; fulfilling anyway would ship stock the catalog took back.
        orderRepository.advanceStatus(order.getOrderId(), order.getStatus(), OrderStatus.CANCELLED);

        fulfillmentService.fulfill(order.getOrderId());

        Order result = orderRepository.findById(order.getOrderId()).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, result.getStatus());
        assertEquals(order.getVersion() + 1, result.getVersion(), "no fulfillment write may have happened");
        assertEquals(1.0, meterRegistry.counter("fulfillment.skipped").count());
    }

    @Test
    void fulfill_preservesOrderTotalAcrossTransitions() {
        Order order = seedOrder();

        fulfillmentService.fulfill(order.getOrderId());

        Order result = orderRepository.findById(order.getOrderId()).orElseThrow();
        assertEquals(0, new BigDecimal("9.99").compareTo(result.getTotalAmount()));
        assertEquals(0, new BigDecimal("9.99").compareTo(result.getItems().get(0).getUnitPrice()));
    }


    @Test
    void fulfill_publishesOneEventPerCommittedTransition() {
        Order order = seedOrder();

        fulfillmentService.fulfill(order.getOrderId());

        ArgumentCaptor<OrderStatusEvent> captor = ArgumentCaptor.forClass(OrderStatusEvent.class);
        Mockito.verify(eventPublisher, Mockito.times(4)).publish(captor.capture());

        assertEquals(
            List.of("PAYMENT_PROCESSING", "PAYMENT_SUCCEEDED", "SHIPMENT_PROCESSING", "FULFILLED"),
            captor.getAllValues().stream().map(OrderStatusEvent::getStatus).toList());

        OrderStatusEvent first = captor.getAllValues().get(0);
        assertEquals("INVENTORY_RESERVED", first.getPreviousStatus());
        assertEquals(order.getCustomerId(), first.getCustomerId());
        assertTrue(first.getCommittedAtEpochMilli() > 0, "events must carry a commit timestamp");
    }

    @Test
    void fulfill_skippedOrder_publishesNothing() {
        Order order = seedOrder();
        orderRepository.advanceStatus(order.getOrderId(), order.getStatus(), OrderStatus.CANCELLED);

        fulfillmentService.fulfill(order.getOrderId());

        Mockito.verifyNoInteractions(eventPublisher);
    }


    @Test
    void fulfill_orderCancelledMidFlight_acknowledgesInsteadOfRetrying() {
        // The realistic race: cancel is only legal from INVENTORY_RESERVED, so it has to land
        // between the worker's read and the worker's first write. That is modelled by letting
        // the cancel commit for real while the repository hands the worker the order as it
        // looked beforehand.
        Order staleRead = seedOrder();
        orderRepository.advanceStatus(staleRead.getOrderId(), OrderStatus.INVENTORY_RESERVED,
            OrderStatus.CANCELLED);

        OrderRepository stale = Mockito.spy(orderRepository);
        Mockito.doReturn(Optional.of(staleRead)).when(stale).findById(staleRead.getOrderId());

        OrderFulfillmentService service = new OrderFulfillmentService(
            stale, auditLogRepository, inventoryRepository, dynamoDb, new OrderStateMachine(),
            paymentSimulator, shipmentSimulator, meterRegistry, eventPublisher);

        // Previously this surfaced as a version conflict the worker rethrew, burning a backoff
        // interval and a receive count before the redelivery reached the terminal-state check
        // and skipped anyway.
        assertDoesNotThrow(() -> service.fulfill(staleRead.getOrderId()),
            "a mid-flight cancel must not be re-thrown for redelivery");

        Order result = orderRepository.findById(staleRead.getOrderId()).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, result.getStatus());
        assertEquals(1.0, meterRegistry.counter("fulfillment.skipped").count());
        assertEquals(0.0, meterRegistry.counter("fulfillment.transient_failure").count());
    }


    @Test
    void fulfill_settlesTheReservationWhenTheOrderShips() {
        Order order = seedOrder();   // seeded as total=100, available=99, reserved=1
        String itemId = order.getItems().get(0).getItemId();

        fulfillmentService.fulfill(order.getOrderId());

        Inventory after = inventoryRepository.findById(itemId).orElseThrow();
        // The unit shipped, so it is no longer held and no longer owned. Leaving reservedQuantity
        // at 1 would conflate "held for an order in flight" with "already sold".
        assertEquals(0, after.getReservedQuantity(), "reservation must be settled on shipment");
        assertEquals(99, after.getTotalQuantity(), "shipped stock must leave totalQuantity");
        assertEquals(99, after.getAvailableQuantity(), "available is untouched by settlement");
        assertEquals(after.getTotalQuantity(), after.getAvailableQuantity() + after.getReservedQuantity());
    }

    @Test
    void fulfill_replayAfterShipping_doesNotSettleTwice() {
        Order order = seedOrder();
        String itemId = order.getItems().get(0).getItemId();

        fulfillmentService.fulfill(order.getOrderId());
        // At-least-once delivery: the same message arrives again after the order shipped.
        fulfillmentService.fulfill(order.getOrderId());

        Inventory after = inventoryRepository.findById(itemId).orElseThrow();
        assertEquals(0, after.getReservedQuantity());
        assertEquals(99, after.getTotalQuantity(), "a replay must not retire the stock twice");
    }

    @Test
    void fulfill_writesTheAuditEntryInTheSameTransactionAsTheStatus() {
        Order order = seedOrder();

        fulfillmentService.fulfill(order.getOrderId());

        // Four transitions, four audit entries — the two can no longer diverge, because a
        // process dying between them would roll the status change back too.
        assertEquals(4, auditLogRepository.findByOrderId(order.getOrderId()).size());
        assertEquals(OrderStatus.FULFILLED,
            orderRepository.findById(order.getOrderId()).orElseThrow().getStatus());
    }

    @Test
    void fulfill_failedOrder_doesNotSettleTheReservation() {
        ReflectionTestUtils.setField(paymentSimulator, "failureMode", "PERMANENT");
        Order order = seedOrder();
        String itemId = order.getItems().get(0).getItemId();

        fulfillmentService.fulfill(order.getOrderId());

        // Nothing shipped, so the stock is still held — it is released by an operator
        // cancelling, not retired by settlement.
        Inventory after = inventoryRepository.findById(itemId).orElseThrow();
        assertEquals(1, after.getReservedQuantity());
        assertEquals(100, after.getTotalQuantity());
    }

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
            .items(List.of(Order.OrderItem.builder()
                .itemId(itemId).quantity(1).unitPrice(new BigDecimal("9.99")).build()))
            .status(OrderStatus.INVENTORY_RESERVED)
            .totalAmount(new BigDecimal("9.99"))
            .version(1L)
            .createdAt(now)
            .updatedAt(now)
            .build();
        orderRepository.save(order);
        return order;
    }
}