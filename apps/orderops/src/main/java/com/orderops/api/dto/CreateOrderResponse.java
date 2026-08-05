package com.orderops.api.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CreateOrderResponse {
    String orderId;
    String status;
    String createdAt;
    boolean replayed;
}
