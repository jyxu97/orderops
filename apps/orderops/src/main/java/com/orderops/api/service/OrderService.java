package com.orderops.api.service;

import com.orderops.api.dto.CreateOrderRequest;
import com.orderops.api.dto.CreateOrderResponse;
import com.orderops.api.dto.GetOrderResponse;
import com.orderops.api.dto.OrderSummaryResponse;
import com.orderops.api.dto.PageResponse;
import com.orderops.api.exception.InsufficientInventoryException;
import com.orderops.api.exception.InventoryNotFoundException;
import com.orderops.api.exception.OrderNotFoundException;
import com.orderops.api.repository.AuditLogRepository;
import com.orderops.api.repository.IdempotencyRepository;
import com.orderops.api.repository.InventoryRepository;
import com.orderops.api.repository.OrderRepository;
import com.orderops.shared.exception.InvalidStateTransitionException;
import com.orderops.shared.model.IdempotencyRecord;
import com.orderops.shared.model.Inventory;
import com.orderops.shared.model.Order;
import com.orderops.shared.model.OrderAuditLog;
import com.orderops.shared.model.Page;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";

    private final OrderRepository orderRepository;
    private final InventoryRepository inventoryRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final AuditLogRepository auditLogRepository;
    private final OrderStateMachine stateMachine;
    private final IdempotencyService idempotencyService;
    private final SqsPublisher sqsPublisher;
    private final MeterRegistry meterRegistry;
    private final DynamoDbClient dynamoDb;

    /**
     * Creates an order atomically using DynamoDB TransactWriteItems.
     *
     * <p>A single transaction atomically:
     * <ol>
     *   <li>Conditionally reserves inventory for every line item (condition: availableQty >= requested)</li>
     *   <li>Writes the order record (condition: attribute_not_exists(orderId))</li>
     *   <li>Writes the idempotency record, if an Idempotency-Key was supplied</li>
     * </ol>
     *
     * <p>If any inventory condition fails the whole transaction is rolled back by DynamoDB.
     * There is no manual rollback code.
     *
     * <p>Idempotency semantics:
     * <ul>
     *   <li>Same key + same body → returns the original response without re-processing</li>
     *   <li>Same key + different body → throws {@link com.orderops.api.exception.IdempotencyConflictException}</li>
     * </ul>
     */
    public CreateOrderResponse createOrder(CreateOrderRequest request, String idempotencyKey) {
        // 1. Idempotency check (Redis fast path → DynamoDB slow path)
        String requestHash = null;
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            requestHash = idempotencyService.computeRequestHash(request);
            CreateOrderResponse cached = idempotencyService.findCachedResponse(idempotencyKey, requestHash);
            if (cached != null) {
                log.info("Returning cached response for Idempotency-Key={}", idempotencyKey);
                return cached;
            }
        }

        // 2. Validate state transition
        stateMachine.validateTransition(OrderStatus.CREATED, OrderStatus.INVENTORY_RESERVED);

        // 3. Snapshot catalog prices and fail fast on an unknown SKU.
        //
        //    This read is not part of the reservation's correctness story — the conditional
        //    updates below still decide whether stock is available. Its purpose is to capture
        //    the price the customer is being charged, so a later catalog change cannot rewrite
        //    the value of an existing order. A price edit racing this read is accepted: the
        //    customer pays the price that was current when the order was priced.
        Map<String, Inventory> catalog = inventoryRepository.findAllById(requestedItemIds(request));
        for (CreateOrderRequest.OrderItemDto item : request.getItems()) {
            if (!catalog.containsKey(item.getItemId())) {
                throw new InventoryNotFoundException(item.getItemId());
            }
        }

        // 4. Build the order object
        String orderId = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        List<Order.OrderItem> orderItems = request.getItems().stream()
            .map(i -> Order.OrderItem.builder()
                .itemId(i.getItemId())
                .quantity(i.getQuantity())
                .unitPrice(catalog.get(i.getItemId()).getUnitPrice())
                .build())
            .collect(Collectors.toList());

        BigDecimal totalAmount = orderItems.stream()
            .map(Order.OrderItem::lineTotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        Order order = Order.builder()
            .orderId(orderId)
            .customerId(request.getCustomerId())
            .items(orderItems)
            .status(OrderStatus.INVENTORY_RESERVED)
            .totalAmount(totalAmount)
            .version(1L)
            .createdAt(now)
            .updatedAt(now)
            .build();

        // 5. Assemble TransactWriteItems:
        //    positions [0 .. N-1] = inventory reserves (one per line item)
        //    position  [N]        = order put
        //    position  [N+1]      = idempotency put (only when key is present)
        List<TransactWriteItem> transactItems = new ArrayList<>();

        for (CreateOrderRequest.OrderItemDto item : request.getItems()) {
            transactItems.add(inventoryRepository.buildReserveTransactItem(item.getItemId(), item.getQuantity()));
        }

        transactItems.add(TransactWriteItem.builder()
            .put(orderRepository.toPutForTransaction(order))
            .build());

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            IdempotencyRecord record = IdempotencyRecord.builder()
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .orderId(orderId)
                .orderStatus(OrderStatus.INVENTORY_RESERVED.name())
                .totalAmount(totalAmount)
                .createdAt(now)
                .build();
            transactItems.add(idempotencyRepository.buildSaveTransactItem(record));
        }

        // 6. Execute transaction — DynamoDB guarantees all-or-nothing
        try {
            dynamoDb.transactWriteItems(TransactWriteItemsRequest.builder()
                .transactItems(transactItems)
                .build());
        } catch (TransactionCanceledException e) {
            // Each CancellationReason maps 1:1 to the TransactWriteItem at the same index.
            // Positions [0..N-1] are inventory items; [N] is the order put; [N+1] is idempotency.
            List<CancellationReason> reasons = e.cancellationReasons();
            int n = request.getItems().size();

            // Check inventory positions [0..N-1] for insufficient stock.
            for (int i = 0; i < n; i++) {
                if (i < reasons.size() && CONDITIONAL_CHECK_FAILED.equals(reasons.get(i).code())) {
                    String itemId = request.getItems().get(i).getItemId();
                    int quantity  = request.getItems().get(i).getQuantity();
                    meterRegistry.counter("orders.inventory_rejected").increment();
                    throw new InsufficientInventoryException(itemId, quantity);
                }
            }

            // Check idempotency position [N+1]: ConditionalCheckFailed here means a concurrent
            // request with the same key won the race. Fetch and return its committed result.
            int idemIdx = n + 1;
            if (idempotencyKey != null && !idempotencyKey.isBlank()
                    && idemIdx < reasons.size()
                    && CONDITIONAL_CHECK_FAILED.equals(reasons.get(idemIdx).code())) {
                log.info("Idempotency race condition detected for key={}, fetching winner's record", idempotencyKey);
                return idempotencyRepository.findByKey(idempotencyKey)
                    .map(rec -> CreateOrderResponse.builder()
                        .orderId(rec.getOrderId())
                        .status(rec.getOrderStatus())
                        .totalAmount(rec.getTotalAmount())
                        .createdAt(rec.getCreatedAt())
                        .replayed(true)
                        .build())
                    .orElseThrow(() -> new RuntimeException(
                        "Idempotency race: ConditionalCheckFailed but record missing for key: " + idempotencyKey));
            }

            throw new RuntimeException("Transaction failed unexpectedly: " + e.getMessage(), e);
        }

        // 7. Write audit log (best-effort, outside transaction)
        auditLogRepository.save(OrderAuditLog.builder()
            .orderId(orderId)
            .timestamp(now)
            .fromStatus(OrderStatus.CREATED.name())
            .toStatus(OrderStatus.INVENTORY_RESERVED.name())
            .reason("Order created")
            .build());

        log.info("Order created orderId={} customerId={} total={}", orderId, request.getCustomerId(), totalAmount);
        meterRegistry.counter("orders.created").increment();

        CreateOrderResponse response = CreateOrderResponse.builder()
            .orderId(orderId)
            .status(OrderStatus.INVENTORY_RESERVED.name())
            .totalAmount(totalAmount)
            .createdAt(now)
            .build();

        // 8. Cache idempotency record in Redis (DynamoDB write already handled in transaction)
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyService.cacheResponseInRedis(idempotencyKey, requestHash, response);
        }

        // 9. Publish to SQS for async fulfillment
        sqsPublisher.publishOrderCreated(orderId);

        return response;
    }

    /**
     * Cancels an order and returns its reserved stock to the catalog in one transaction:
     * every line item is released and the order moves to CANCELLED together, or neither happens.
     *
     * <p>Cancellation is only permitted from states the state machine allows — before the worker
     * starts charging, or from manual review as an operator resolution. An order already in the
     * fulfillment pipeline cannot be pulled back.
     *
     * <p>The call is idempotent: cancelling an already-cancelled order returns its current state
     * rather than an error, so a client retrying after a timeout does not release stock twice.
     */
    public GetOrderResponse cancelOrder(String orderId) {
        Order order = loadOrder(orderId);

        if (order.getStatus() == OrderStatus.CANCELLED) {
            log.info("Order {} is already cancelled, returning current state", orderId);
            return toResponse(order);
        }

        // Surfaces as 409 with the offending transition named.
        stateMachine.validateTransition(order.getStatus(), OrderStatus.CANCELLED);

        String now = Instant.now().toString();
        List<TransactWriteItem> transactItems = new ArrayList<>();
        for (Order.OrderItem item : order.getItems()) {
            transactItems.add(inventoryRepository.buildReleaseTransactItem(item.getItemId(), item.getQuantity()));
        }
        transactItems.add(orderRepository.buildStatusTransitionTransactItem(
            orderId, order.getStatus(), OrderStatus.CANCELLED, order.getVersion(), now));

        try {
            dynamoDb.transactWriteItems(TransactWriteItemsRequest.builder()
                .transactItems(transactItems)
                .build());
        } catch (TransactionCanceledException e) {
            // The order update sits at the last position. A failed condition there means someone
            // else changed the order between our read and our write — a concurrent cancel, or the
            // worker advancing the order. Re-read to decide which.
            return resolveCancelConflict(orderId, e);
        }

        auditLogRepository.save(OrderAuditLog.builder()
            .orderId(orderId)
            .timestamp(now)
            .fromStatus(order.getStatus().name())
            .toStatus(OrderStatus.CANCELLED.name())
            .reason("Order cancelled, reserved inventory released")
            .build());

        log.info("Order {} cancelled from {}, released {} line item(s)",
            orderId, order.getStatus(), order.getItems().size());
        meterRegistry.counter("orders.cancelled").increment();

        return toResponse(Order.builder()
            .orderId(order.getOrderId())
            .customerId(order.getCustomerId())
            .items(order.getItems())
            .status(OrderStatus.CANCELLED)
            .totalAmount(order.getTotalAmount())
            .version(order.getVersion() + 1)
            .createdAt(order.getCreatedAt())
            .updatedAt(now)
            .build());
    }

    /** Order history for one customer, newest first. */
    public PageResponse<OrderSummaryResponse> listOrdersByCustomer(String customerId, int limit, String cursor) {
        return toPageResponse(orderRepository.findByCustomerId(customerId, limit, cursor));
    }

    /** Orders currently in one status, most recently updated first. */
    public PageResponse<OrderSummaryResponse> listOrdersByStatus(OrderStatus status, int limit, String cursor) {
        return toPageResponse(orderRepository.findByStatus(status, limit, cursor));
    }

    public GetOrderResponse getOrder(String orderId) {
        return toResponse(loadOrder(orderId));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Order loadOrder(String orderId) {
        return orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    private static Set<String> requestedItemIds(CreateOrderRequest request) {
        return request.getItems().stream()
            .map(CreateOrderRequest.OrderItemDto::getItemId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Decides what a lost cancel race actually means by re-reading the order.
     *
     * <p>If the winner also cancelled, this call's intent was satisfied and we report success.
     * Otherwise the order moved on and cancellation is genuinely no longer allowed.
     */
    private GetOrderResponse resolveCancelConflict(String orderId, TransactionCanceledException e) {
        Order current = loadOrder(orderId);

        if (current.getStatus() == OrderStatus.CANCELLED) {
            log.info("Concurrent cancel of order {} won the race; returning its result", orderId);
            return toResponse(current);
        }

        boolean orderConditionFailed = !e.cancellationReasons().isEmpty()
            && CONDITIONAL_CHECK_FAILED.equals(
                e.cancellationReasons().get(e.cancellationReasons().size() - 1).code());

        if (orderConditionFailed) {
            log.warn("Cancel of order {} lost a race; order is now {}", orderId, current.getStatus());
            throw new InvalidStateTransitionException(
                current.getStatus().name(), OrderStatus.CANCELLED.name());
        }

        // A release condition failed instead — reservedQuantity was lower than the order claims.
        // That should be impossible while the order holds the reservation, so surface it loudly
        // rather than reporting a cancellation that did not happen.
        throw new IllegalStateException(
            "Cancel of order " + orderId + " failed to release inventory: " + e.getMessage(), e);
    }

    private GetOrderResponse toResponse(Order order) {
        List<GetOrderResponse.OrderItemDto> items = order.getItems().stream()
            .map(i -> GetOrderResponse.OrderItemDto.builder()
                .itemId(i.getItemId())
                .quantity(i.getQuantity())
                .unitPrice(i.getUnitPrice())
                .lineTotal(i.lineTotal())
                .build())
            .collect(Collectors.toList());

        return GetOrderResponse.builder()
            .orderId(order.getOrderId())
            .customerId(order.getCustomerId())
            .items(items)
            .status(order.getStatus().name())
            .totalAmount(order.getTotalAmount())
            .cancellable(stateMachine.isCancellable(order.getStatus()))
            .version(order.getVersion())
            .createdAt(order.getCreatedAt())
            .updatedAt(order.getUpdatedAt())
            .build();
    }

    private static PageResponse<OrderSummaryResponse> toPageResponse(Page<Order> page) {
        List<OrderSummaryResponse> summaries = page.getItems().stream()
            .map(OrderService::toSummary)
            .collect(Collectors.toList());

        return PageResponse.<OrderSummaryResponse>builder()
            .items(summaries)
            .nextCursor(page.getNextCursor())
            .build();
    }

    private static OrderSummaryResponse toSummary(Order order) {
        return OrderSummaryResponse.builder()
            .orderId(order.getOrderId())
            .customerId(order.getCustomerId())
            .status(order.getStatus().name())
            .itemCount(order.getItems().size())
            .totalQuantity(order.getItems().stream().mapToInt(Order.OrderItem::getQuantity).sum())
            .totalAmount(order.getTotalAmount())
            .version(order.getVersion())
            .createdAt(order.getCreatedAt())
            .updatedAt(order.getUpdatedAt())
            .build();
    }
}
