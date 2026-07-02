package com.orderops.api.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class GetOrderResponse {
    String orderId;
    String customerId;
    List<OrderItemDto> items;
    String status;
    long version;
    String createdAt;
    String updatedAt;

    @Value
    @Builder
    public static class OrderItemDto {
        String itemId;
        int quantity;
    }
}
