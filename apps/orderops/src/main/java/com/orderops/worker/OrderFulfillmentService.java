package com.orderops.worker;

import com.orderops.api.repository.AuditLogRepository;
import com.orderops.api.repository.OrderRepository;
import com.orderops.realtime.OrderEventPublisher;
import com.orderops.shared.event.OrderStatusEvent;
import com.orderops.shared.model.Order;
import com.orderops.shared.model.OrderAuditLog;
import com.orderops.shared.state.OrderStateMachine;
import com.orderops.shared.state.OrderStatus;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

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
    private final AuditLogRepository auditLogRepository;
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
        return status == OrderStatus.FULFILLED
            || status == OrderStatus.NEEDS_MANUAL_REVIEW
            || status == OrderStatus.CANCELLED;
    }

    /**
     * Validates the transition, persists the new status to DynamoDB, writes an audit log,
     * and returns the updated in-memory {@link Order} for chaining subsequent transitions.
     */
    private Order applyTransition(Order order, OrderStatus newStatus, String reason) {
        stateMachine.validateTransition(order.getStatus(), newStatus);
        orderRepository.updateStatus(order.getOrderId(), newStatus, order.getVersion());

        String now = Instant.now().toString();
        auditLogRepository.save(OrderAuditLog.builder()
            .orderId(order.getOrderId())
            .timestamp(now)
            .fromStatus(order.getStatus().name())
            .toStatus(newStatus.name())
            .reason(reason)
            .build());

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
