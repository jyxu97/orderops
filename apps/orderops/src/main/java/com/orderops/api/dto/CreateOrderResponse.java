package com.orderops.api.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class CreateOrderResponse {
    String orderId;
    String status;
    BigDecimal totalAmount;
    String createdAt;
    boolean replayed;
}
