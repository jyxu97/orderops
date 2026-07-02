package com.orderops.worker;

import com.orderops.api.repository.AuditLogRepository;
import com.orderops.api.repository.OrderRepository;
import com.orderops.shared.model.Order;
import com.orderops.shared.model.OrderAuditLog;
import com.orderops.shared.state.OrderStateMachine;
import com.orderops.shared.state.OrderStatus;
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

    public void fulfill(String orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        // Worker idempotency: skip orders already in a terminal state
        if (isTerminal(order.getStatus())) {
            log.info("Order {} already terminal ({}), skipping", orderId, order.getStatus());
            return;
        }

        try {
            // INVENTORY_RESERVED → PAYMENT_PROCESSING
            order = applyTransition(order, OrderStatus.PAYMENT_PROCESSING, "Processing payment");

            boolean paymentOk = paymentSimulator.process(orderId);

            if (!paymentOk) {
                order = applyTransition(order, OrderStatus.FAILED, "Payment declined");
                applyTransition(order, OrderStatus.NEEDS_MANUAL_REVIEW, "Queued for manual review");
                return;
            }

            // PAYMENT_PROCESSING → PAYMENT_SUCCEEDED
            order = applyTransition(order, OrderStatus.PAYMENT_SUCCEEDED, "Payment authorized");

            // PAYMENT_SUCCEEDED → SHIPMENT_PROCESSING
            order = applyTransition(order, OrderStatus.SHIPMENT_PROCESSING, "Processing shipment");

            boolean shipmentOk = shipmentSimulator.process(orderId);

            if (!shipmentOk) {
                order = applyTransition(order, OrderStatus.FAILED, "Shipment failed");
                applyTransition(order, OrderStatus.NEEDS_MANUAL_REVIEW, "Queued for manual review");
                return;
            }

            // SHIPMENT_PROCESSING → FULFILLED
            applyTransition(order, OrderStatus.FULFILLED, "Order delivered");
            log.info("Order {} fulfilled successfully", orderId);

        } catch (RuntimeException e) {
            log.error("Fulfillment failed for orderId={}: {}", orderId, e.getMessage());
            throw e; // re-throw so SQS does not delete the message (will retry / DLQ)
        }
    }

    private boolean isTerminal(OrderStatus status) {
        return status == OrderStatus.FULFILLED || status == OrderStatus.NEEDS_MANUAL_REVIEW;
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

        return Order.builder()
            .orderId(order.getOrderId())
            .customerId(order.getCustomerId())
            .items(order.getItems())
            .status(newStatus)
            .version(order.getVersion() + 1)
            .createdAt(order.getCreatedAt())
            .updatedAt(now)
            .build();
    }
}