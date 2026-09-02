package com.orderops.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * A failed order plus the reason it failed, for the operations failures view.
 *
 * <p>The reason lives in the audit log rather than on the order record, so it is joined in
 * here instead of being duplicated onto every order.
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FailedOrderResponse {
    String orderId;
    String customerId;
    String status;
    BigDecimal totalAmount;
    /** Last recorded transition reason, e.g. "Payment declined". Null if no audit entry exists. */
    String lastFailureReason;
    String failedAt;
    boolean cancellable;
    String createdAt;
    String updatedAt;
}
