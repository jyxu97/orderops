package com.orderops.shared.state;

public enum OrderStatus {
    CREATED,
    INVENTORY_RESERVED,
    PAYMENT_PROCESSING,
    PAYMENT_SUCCEEDED,
    SHIPMENT_PROCESSING,
    FULFILLED,
    FAILED,
    NEEDS_MANUAL_REVIEW
}
