package com.orderops.api.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.List;

@Value
@Builder
public class GetOrderResponse {
    String orderId;
    String customerId;
    List<OrderItemDto> items;
    String status;
    BigDecimal totalAmount;
    boolean cancellable;
    long version;
    String createdAt;
    String updatedAt;

    @Value
    @Builder
    public static class OrderItemDto {
        String itemId;
        int quantity;
        BigDecimal unitPrice;
        BigDecimal lineTotal;
    }
}
