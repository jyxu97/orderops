package com.orderops.shared.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class IdempotencyRecord {
    private String idempotencyKey;
    private String requestHash;
    private String orderId;
    private String orderStatus;
    private String createdAt;
}
