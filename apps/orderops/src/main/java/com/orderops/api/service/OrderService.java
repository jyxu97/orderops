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
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${tables.idempotency:IdempotencyRecords}")
    private String idempotencyTable;

    public CreateOrderResponse createOrder(CreateOrderRequest request) {
        String orderId = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        // Validate the CREATED -> INVENTORY_RESERVED transition
        stateMachine.validateTransition(OrderStatus.CREATED, OrderStatus.INVENTORY_RESERVED);

        // Reserve inventory for each item (conditional update per item)
        for (CreateOrderRequest.OrderItemDto item : request.getItems()) {
            boolean reserved = inventoryRepository.reserveInventory(item.getItemId(), item.getQuantity());
            if (!reserved) {
                // Roll back already-reserved items
                rollbackReservedItems(request.getItems(), item.getItemId());
                throw new InsufficientInventoryException(item.getItemId(), item.getQuantity());
            }
        }

        // Build and persist the order
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

        // Write audit log for the state transition
        auditLogRepository.save(OrderAuditLog.builder()
            .orderId(orderId)
            .timestamp(now)
            .fromStatus(OrderStatus.CREATED.name())
            .toStatus(OrderStatus.INVENTORY_RESERVED.name())
            .reason("Order created")
            .build());

        log.info("Order created orderId={} customerId={}", orderId, request.getCustomerId());

        return CreateOrderResponse.builder()
            .orderId(orderId)
            .status(OrderStatus.INVENTORY_RESERVED.name())
            .createdAt(now)
            .build();
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

    /**
     * Releases previously reserved inventory for items that were successfully reserved
     * before a failure occurred at {@code failedItemId}.
     */
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
