package com.orderops.api.service;

import com.orderops.api.dto.CreateOrderRequest;
import com.orderops.api.dto.CreateOrderResponse;
import com.orderops.api.dto.GetOrderResponse;
import com.orderops.api.exception.InsufficientInventoryException;
import com.orderops.api.exception.OrderNotFoundException;
import com.orderops.api.repository.AuditLogRepository;
import com.orderops.api.repository.InventoryRepository;
import com.orderops.api.repository.OrderRepository;
import com.orderops.shared.model.Order;
import com.orderops.shared.model.OrderAuditLog;
import com.orderops.shared.state.OrderStateMachine;
import com.orderops.shared.state.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final InventoryRepository inventoryRepository;
    private final AuditLogRepository auditLogRepository;
    private final OrderStateMachine stateMachine;
    private final IdempotencyService idempotencyService;
    private final SqsPublisher sqsPublisher;

    /**
     * Creates an order, with optional idempotency support.
     *
     * <p>If {@code idempotencyKey} is provided:
     * <ul>
     *   <li>Same key + same body → returns the original response without re-processing</li>
     *   <li>Same key + different body → throws {@link com.orderops.api.exception.IdempotencyConflictException}</li>
     * </ul>
     */
    public CreateOrderResponse createOrder(CreateOrderRequest request, String idempotencyKey) {
        // 1. Idempotency check
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

        // 3. Reserve inventory for each item
        for (CreateOrderRequest.OrderItemDto item : request.getItems()) {
            boolean reserved = inventoryRepository.reserveInventory(item.getItemId(), item.getQuantity());
            if (!reserved) {
                rollbackReservedItems(request.getItems(), item.getItemId());
                throw new InsufficientInventoryException(item.getItemId(), item.getQuantity());
            }
        }

        // 4. Build and persist the order
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

        orderRepository.save(order);

        // 5. Write audit log
        auditLogRepository.save(OrderAuditLog.builder()
            .orderId(orderId)
            .timestamp(now)
            .fromStatus(OrderStatus.CREATED.name())
            .toStatus(OrderStatus.INVENTORY_RESERVED.name())
            .reason("Order created")
            .build());

        log.info("Order created orderId={} customerId={}", orderId, request.getCustomerId());

        CreateOrderResponse response = CreateOrderResponse.builder()
            .orderId(orderId)
            .status(OrderStatus.INVENTORY_RESERVED.name())
            .createdAt(now)
            .build();

        // 6. Persist idempotency record
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyService.store(idempotencyKey, requestHash, response);
        }

        // 7. Publish to SQS for async fulfillment
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

    private void rollbackReservedItems(List<CreateOrderRequest.OrderItemDto> items, String failedItemId) {
        for (CreateOrderRequest.OrderItemDto item : items) {
            if (item.getItemId().equals(failedItemId)) {
                break;
            }
            try {
                inventoryRepository.releaseInventory(item.getItemId(), item.getQuantity());
                log.info("Rolled back reservation itemId={} qty={}", item.getItemId(), item.getQuantity());
            } catch (Exception e) {
                log.error("Failed to rollback reservation itemId={}", item.getItemId(), e);
            }
        }
    }
}
