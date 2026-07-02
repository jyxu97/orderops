package com.orderops.shared.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class OrderAuditLog {
    String orderId;
    String timestamp;
    String fromStatus;
    String toStatus;
    String reason;
}
