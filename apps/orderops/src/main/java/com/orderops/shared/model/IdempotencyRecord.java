package com.orderops.shared.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class IdempotencyRecord {
    private String idempotencyKey;
    private String requestHash;
    private String orderId;
    private String orderStatus;
    /**
     * Order total, stored so that a replay can be answered from the idempotency record alone
     * without a second read of the Orders table.
     */
    private BigDecimal totalAmount;
    private String createdAt;
}
