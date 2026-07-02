package com.orderops.api.dto;

import lombok.Data;

import java.util.List;

@Data
public class CreateOrderRequest {
    private String customerId;
    private List<OrderItemDto> items;

    @Data
    public static class OrderItemDto {
        private String itemId;
        private int quantity;
    }
}
