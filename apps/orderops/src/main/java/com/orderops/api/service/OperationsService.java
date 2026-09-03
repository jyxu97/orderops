package com.orderops.api.service;

import com.orderops.api.dto.FailedOrderResponse;
import com.orderops.api.dto.OpsOverviewResponse;
import com.orderops.api.dto.OrderSummaryResponse;
import com.orderops.api.dto.QueueHealthResponse;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Read-only views for the operations dashboard.
 *
 * <p>Every query here is served by {@code GSI2_StatusUpdatedAt}; nothing scans the Orders
 * table. "Recent orders across all statuses" is a bounded fan-out — one indexed query per
 * status, merged in memory — because there is no index that orders every order by update
 * time, and adding one would mean a single-partition index for the whole table.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperationsService {

    /** Statuses that represent a fulfillment that did not complete. */
    private static final List<OrderStatus> FAILURE_STATUSES =
        List.of(OrderStatus.FAILED, OrderStatus.NEEDS_MANUAL_REVIEW);

    /**
     * How far back to look for the transition that actually failed.
     *
     * <p>A failed order's newest audit entry is usually the routing step into manual review, so
     * the useful reason sits one or two entries earlier. Four covers the longest failure path
     * the state machine allows with room to spare.
     */
    private static final int AUDIT_LOOKBACK = 4;

    private final OrderRepository orderRepository;
    private final AuditLogRepository auditLogRepository;
    private final OrderStateMachine stateMachine;
    private final SqsQueueInspector queueInspector;

    public OpsOverviewResponse overview(int recentLimit) {
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        boolean capped = false;

        for (OrderStatus status : OrderStatus.values()) {
            OrderRepository.StatusCount count = orderRepository.countByStatus(status);
            statusCounts.put(status.name(), count.count());
            capped |= count.capped();
        }

        return OpsOverviewResponse.builder()
            .statusCounts(statusCounts)
            .countsCapped(capped)
            .recentOrders(recentOrders(recentLimit))
            .queueHealth(queueInspector.inspect())
            .generatedAt(Instant.now().toString())
            .build();
    }

    /**
     * The {@code limit} most recently updated orders across every status.
     *
     * <p>Each status is queried for at most {@code limit} rows, so the merge sees at most
     * {@code limit × statuses} candidates regardless of table size.
     */
    public List<OrderSummaryResponse> recentOrders(int limit) {
        return Stream.of(OrderStatus.values())
            .flatMap(status -> orderRepository.findByStatus(status, limit, null).getItems().stream())
            .sorted(Comparator.comparing(Order::getUpdatedAt).reversed())
            .limit(limit)
            .map(OrderSummaryResponse::from)
            .collect(Collectors.toList());
    }

    /**
     * Orders that failed, newest first, each joined with its last transition reason.
     *
     * <p>The reason comes from a single-item descending audit query per order, which is why
     * {@code limit} is bounded by the caller: this view costs one extra read per row.
     */
    public List<FailedOrderResponse> failures(int limit) {
        return FAILURE_STATUSES.stream()
            .flatMap(status -> orderRepository.findByStatus(status, limit, null).getItems().stream())
            .sorted(Comparator.comparing(Order::getUpdatedAt).reversed())
            .limit(limit)
            .map(this::toFailedOrder)
            .collect(Collectors.toList());
    }

    public QueueHealthResponse queueHealth() {
        return queueInspector.inspect();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private FailedOrderResponse toFailedOrder(Order order) {
        List<OrderAuditLog> recent =
            auditLogRepository.findRecentByOrderId(order.getOrderId(), AUDIT_LOOKBACK);

        Optional<OrderAuditLog> cause = causeOf(recent);

        return FailedOrderResponse.builder()
            .orderId(order.getOrderId())
            .customerId(order.getCustomerId())
            .status(order.getStatus().name())
            .totalAmount(order.getTotalAmount())
            .lastFailureReason(cause.map(OrderAuditLog::getReason).orElse(null))
            .failedAt(cause.map(OrderAuditLog::getTimestamp).orElse(order.getUpdatedAt()))
            .cancellable(stateMachine.isCancellable(order.getStatus()))
            .createdAt(order.getCreatedAt())
            .updatedAt(order.getUpdatedAt())
            .build();
    }

    /**
     * Picks the audit entry that explains the failure.
     *
     * <p>The newest entry is the wrong one to show: an order in manual review got there via
     * FAILED → NEEDS_MANUAL_REVIEW, whose reason is the routing step ("Queued for manual
     * review") rather than the cause. The transition *into* FAILED is what carries the real
     * reason ("Payment declined", "Shipment failed"), so that is preferred, with the newest
     * entry as a fallback for an order that failed some other way.
     *
     * @param recent audit entries newest first
     */
    private static Optional<OrderAuditLog> causeOf(List<OrderAuditLog> recent) {
        return recent.stream()
            .filter(entry -> OrderStatus.FAILED.name().equals(entry.getToStatus()))
            .findFirst()
            .or(() -> recent.stream().findFirst());
    }

}
