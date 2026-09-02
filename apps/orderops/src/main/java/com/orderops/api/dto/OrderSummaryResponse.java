package com.orderops.api.dto;

import com.orderops.shared.model.Order;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * Compact order representation for list views (order history, operations dashboard).
 *
 * <p>Line items are deliberately excluded — list rows only need to show identity, status,
 * value and timestamps. Clients fetch {@link GetOrderResponse} for the full record.
 */
@Value
@Builder
public class OrderSummaryResponse {
    String orderId;
    String customerId;
    String status;
    int itemCount;
    int totalQuantity;
    BigDecimal totalAmount;
    long version;
    String createdAt;
    String updatedAt;

    public static OrderSummaryResponse from(Order order) {
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
