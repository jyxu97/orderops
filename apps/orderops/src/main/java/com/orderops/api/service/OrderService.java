package com.orderops.api.service;

import com.orderops.api.dto.CreateOrderRequest;
import com.orderops.api.dto.CreateOrderResponse;
import com.orderops.api.dto.GetOrderResponse;
import com.orderops.api.exception.InsufficientInventoryException;
import com.orderops.api.exception.OrderNotFoundException;
import com.orderops.api.repository.AuditLogRepository;
import com.orderops.api.repository.IdempotencyRepository;
import com.orderops.api.repository.InventoryRepository;
import com.orderops.api.repository.OrderRepository;
import com.orderops.shared.model.IdempotencyRecord;
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
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

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

        // 3. Build the order object
        String orderId = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        List<Order.OrderItem> orderItems = request.getItems().stream()
            .map(i -> Order.OrderItem.builder()
                .itemId(i.getItemId())
                .quantity(i.getQuantity())
                .build())
            .collect(Collectors.toList());

        Order order = Order.builder()
            .orderId(orderId)
            .customerId(request.getCustomerId())
            .items(orderItems)
            .status(OrderStatus.INVENTORY_RESERVED)
            .version(1L)
            .createdAt(now)
            .updatedAt(now)
            .build();

        // 4. Assemble TransactWriteItems:
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
                .createdAt(now)
                .build();
            transactItems.add(idempotencyRepository.buildSaveTransactItem(record));
        }

        // 5. Execute transaction — DynamoDB guarantees all-or-nothing
        try {
            dynamoDb.transactWriteItems(TransactWriteItemsRequest.builder()
                .transactItems(transactItems)
                .build());
        } catch (TransactionCanceledException e) {
            // Each CancellationReason maps 1:1 to the TransactWriteItem at the same index.
            // Positions [0..N-1] are inventory items; check each for ConditionalCheckFailed.
            List<CancellationReason> reasons = e.cancellationReasons();
            for (int i = 0; i < request.getItems().size(); i++) {
                if (i < reasons.size() && "ConditionalCheckFailed".equals(reasons.get(i).code())) {
                    String itemId  = request.getItems().get(i).getItemId();
                    int quantity   = request.getItems().get(i).getQuantity();
                    meterRegistry.counter("orders.inventory_rejected").increment();
                    throw new InsufficientInventoryException(itemId, quantity);
                }
            }
            throw new RuntimeException("Transaction failed unexpectedly: " + e.getMessage(), e);
        }

        // 6. Write audit log (best-effort, outside transaction)
        auditLogRepository.save(OrderAuditLog.builder()
            .orderId(orderId)
            .timestamp(now)
            .fromStatus(OrderStatus.CREATED.name())
            .toStatus(OrderStatus.INVENTORY_RESERVED.name())
            .reason("Order created")
            .build());

        log.info("Order created orderId={} customerId={}", orderId, request.getCustomerId());
        meterRegistry.counter("orders.created").increment();

        CreateOrderResponse response = CreateOrderResponse.builder()
            .orderId(orderId)
            .status(OrderStatus.INVENTORY_RESERVED.name())
            .createdAt(now)
            .build();

        // 7. Cache idempotency record in Redis (DynamoDB write already handled in transaction)
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyService.cacheResponseInRedis(idempotencyKey, requestHash, response);
        }

        // 8. Publish to SQS for async fulfillment
        sqsPublisher.publishOrderCreated(orderId);

        return response;
    }

    public GetOrderResponse getOrder(String orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));

        List<GetOrderResponse.OrderItemDto> items = order.getItems().stream()
            .map(i -> GetOrderResponse.OrderItemDto.builder()
                .itemId(i.getItemId())
                .quantity(i.getQuantity())
                .build())
            .collect(Collectors.toList());

        return GetOrderResponse.builder()
            .orderId(order.getOrderId())
            .customerId(order.getCustomerId())
            .items(items)
            .status(order.getStatus().name())
            .version(order.getVersion())
            .createdAt(order.getCreatedAt())
            .updatedAt(order.getUpdatedAt())
            .build();
    }
}