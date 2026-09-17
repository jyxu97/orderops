package com.orderops.worker;

import com.orderops.api.repository.AuditLogRepository;
import com.orderops.api.repository.InventoryRepository;
import com.orderops.api.repository.OrderRepository;
import com.orderops.realtime.OrderEventPublisher;
import com.orderops.shared.exception.OrderStatusConflictException;
import com.orderops.shared.event.OrderStatusEvent;
import com.orderops.shared.model.Order;
import com.orderops.shared.model.OrderAuditLog;
import com.orderops.shared.state.OrderStateMachine;
import com.orderops.shared.state.OrderStatus;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives an order through its full fulfillment lifecycle:
 *
 * <pre>
 * INVENTORY_RESERVED
 *   → PAYMENT_PROCESSING
 *   → PAYMENT_SUCCEEDED  (or FAILED → NEEDS_MANUAL_REVIEW)
 *   → SHIPMENT_PROCESSING
 *   → FULFILLED          (or FAILED → NEEDS_MANUAL_REVIEW)
 * </pre>
 *
 * <p>Resume-aware: if a transient failure left the order in an intermediate state, the next
 * invocation picks up from the current status rather than restarting from scratch.
 *
 * <p>This service is called by {@link FulfillmentWorker} for each SQS message, but can also be
 * invoked directly in tests without an SQS dependency.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderFulfillmentService {

    private final OrderRepository orderRepository;
    private static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";

    private final AuditLogRepository auditLogRepository;
    private final InventoryRepository inventoryRepository;
    private final DynamoDbClient dynamoDb;
    private final OrderStateMachine stateMachine;
    private final PaymentSimulator paymentSimulator;
    private final ShipmentSimulator shipmentSimulator;
    private final MeterRegistry meterRegistry;
    private final OrderEventPublisher eventPublisher;

    public void fulfill(String orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        // Worker idempotency: skip orders already in a terminal state
        if (isTerminal(order.getStatus())) {
            log.info("Order {} already terminal ({}), skipping", orderId, order.getStatus());
            meterRegistry.counter("fulfillment.skipped").increment();
            return;
        }

        try {
            // Resume from wherever the order left off after a previous transient failure.

            if (order.getStatus() == OrderStatus.INVENTORY_RESERVED) {
                order = applyTransition(order, OrderStatus.PAYMENT_PROCESSING, "Processing payment");
            }

            if (order.getStatus() == OrderStatus.PAYMENT_PROCESSING) {
                boolean paymentOk = paymentSimulator.process(orderId);
                if (!paymentOk) {
                    order = applyTransition(order, OrderStatus.FAILED, "Payment declined");
                    applyTransition(order, OrderStatus.NEEDS_MANUAL_REVIEW, "Queued for manual review");
                    meterRegistry.counter("fulfillment.manual_review", "stage", "payment").increment();
                    return;
                }
                order = applyTransition(order, OrderStatus.PAYMENT_SUCCEEDED, "Payment authorized");
            }

            if (order.getStatus() == OrderStatus.PAYMENT_SUCCEEDED) {
                order = applyTransition(order, OrderStatus.SHIPMENT_PROCESSING, "Processing shipment");
            }

            if (order.getStatus() == OrderStatus.SHIPMENT_PROCESSING) {
                boolean shipmentOk = shipmentSimulator.process(orderId);
                if (!shipmentOk) {
                    order = applyTransition(order, OrderStatus.FAILED, "Shipment failed");
                    applyTransition(order, OrderStatus.NEEDS_MANUAL_REVIEW, "Queued for manual review");
                    meterRegistry.counter("fulfillment.manual_review", "stage", "shipment").increment();
                    return;
                }
                applyTransition(order, OrderStatus.FULFILLED, "Order delivered");
            }

            log.info("Order {} fulfilled successfully", orderId);
            meterRegistry.counter("fulfillment.fulfilled").increment();

        } catch (OrderStatusConflictException e) {
            // Another writer advanced this order while the attempt was in flight — in practice
            // a customer cancelling, or a concurrent duplicate delivery. If it landed somewhere
            // terminal there is nothing left to do, so the message is acknowledged rather than
            // retried: redelivering it would only reach the terminal-state check at the top of
            // this method and skip, after burning a backoff interval and a receive count.
            if (isTerminal(e.getActualStatus())) {
                log.info("Order {} reached {} while this attempt was in flight, nothing to do",
                    orderId, e.getActualStatus());
                meterRegistry.counter("fulfillment.skipped").increment();
                return;
            }
            // Anywhere else is unexpected: only cancel and this worker write an order's status.
            log.error("Fulfillment of order {} lost a race to an unexpected status {}",
                orderId, e.getActualStatus());
            meterRegistry.counter("fulfillment.transient_failure").increment();
            throw e;

        } catch (RuntimeException e) {
            log.error("Fulfillment failed for orderId={}: {}", orderId, e.getMessage());
            meterRegistry.counter("fulfillment.transient_failure").increment();
            throw e; // re-throw so SQS does not delete the message (will retry / DLQ)
        }
    }

    /**
     * States from which the worker must not act.
     *
     * <p>CANCELLED belongs here for a correctness reason, not just efficiency: a customer can
     * cancel while the fulfillment message is still in flight, and the cancellation has already
     * released the reservation. Fulfilling the order at that point would ship stock the catalog
     * has taken back.
     */
    private boolean isTerminal(OrderStatus status) {
        if (status == null) {
            return false;   // order not found — not a terminal state, let it surface
        }
        return status == OrderStatus.FULFILLED
            || status == OrderStatus.NEEDS_MANUAL_REVIEW
            || status == OrderStatus.CANCELLED;
    }

    /**
     * Validates the transition, persists the new status to DynamoDB, writes an audit log,
     * and returns the updated in-memory {@link Order} for chaining subsequent transitions.
     */
    /**
     * Validates, commits and announces one transition.
     *
     * <p>The order update and its audit entry go in a single transaction, and the move to
     * FULFILLED additionally settles every line item's reservation. Previously the status
     * change was one conditional update followed by a best-effort audit write, so a process
     * dying in between produced a transition with no audit entry — a gap that shows up in the
     * operations failures view, which reads exactly that entry to explain a failure.
     *
     * <p>Bundling the settlement here is also what makes it safe against redelivery: the
     * order's own condition ({@code status = :expectedStatus}) fails on a replay, and DynamoDB
     * rolls the settlement back with it. A settle issued as its own call would apply twice.
     */
    private Order applyTransition(Order order, OrderStatus newStatus, String reason) {
        stateMachine.validateTransition(order.getStatus(), newStatus);

        String now = Instant.now().toString();
        List<TransactWriteItem> writes = new ArrayList<>();

        // Position [0] is the order, which is what the conflict handling below inspects.
        writes.add(orderRepository.buildAdvanceStatusTransactItem(
            order.getOrderId(), order.getStatus(), newStatus, now));
        writes.add(auditLogRepository.buildSaveTransactItem(OrderAuditLog.builder()
            .orderId(order.getOrderId())
            .timestamp(now)
            .fromStatus(order.getStatus().name())
            .toStatus(newStatus.name())
            .reason(reason)
            .build()));

        if (newStatus == OrderStatus.FULFILLED) {
            // The stock has physically shipped: stop holding it and stop owning it.
            order.getItems().forEach(item ->
                writes.add(inventoryRepository.buildSettleTransactItem(item.getItemId(), item.getQuantity())));
        }

        try {
            dynamoDb.transactWriteItems(TransactWriteItemsRequest.builder()
                .transactItems(writes)
                .build());
        } catch (TransactionCanceledException e) {
            List<CancellationReason> reasons = e.cancellationReasons();
            CancellationReason orderReason = reasons.isEmpty() ? null : reasons.get(0);

            if (orderReason != null && CONDITIONAL_CHECK_FAILED.equals(orderReason.code())) {
                throw new OrderStatusConflictException(
                    order.getOrderId(), order.getStatus(), OrderRepository.statusFrom(orderReason));
            }
            // An inventory or audit condition failed instead. Nothing here is expected to fail
            // once the order's own check passed, so surface it rather than dressing it up.
            throw new IllegalStateException(
                "Transition of order " + order.getOrderId() + " to " + newStatus
                    + " was cancelled: " + e.getMessage(), e);
        }

        log.info("Order {} {} → {}", order.getOrderId(), order.getStatus(), newStatus);

        // Published only after the write committed, so a subscriber never sees a status that
        // a subsequent read of DynamoDB would contradict.
        eventPublisher.publish(OrderStatusEvent.statusChanged(
            order.getOrderId(), order.getCustomerId(), order.getStatus(), newStatus, reason));

        return Order.builder()
            .orderId(order.getOrderId())
            .customerId(order.getCustomerId())
            .items(order.getItems())
            .status(newStatus)
            .totalAmount(order.getTotalAmount())
            .version(order.getVersion() + 1)
            .createdAt(order.getCreatedAt())
            .updatedAt(now)
            .build();
    }
}
