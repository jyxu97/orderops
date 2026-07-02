package com.orderops.api.exception;

public class InsufficientInventoryException extends RuntimeException {

    public InsufficientInventoryException(String itemId, int requested) {
        super("Insufficient inventory for itemId=" + itemId + ", requested=" + requested);
    }
}
