package com.orderops.shared.model;

import com.orderops.shared.state.OrderStatus;
import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class Order {
    String orderId;
    String customerId;
    List<OrderItem> items;
    OrderStatus status;
    long version;
    String createdAt;
    String updatedAt;

    @Value
    @Builder
    public static class OrderItem {
        String itemId;
        int quantity;
    }
}
