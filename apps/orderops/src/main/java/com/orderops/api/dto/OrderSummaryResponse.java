package com.orderops.api.dto;

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
}
