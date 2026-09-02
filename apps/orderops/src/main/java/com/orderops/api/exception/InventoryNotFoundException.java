package com.orderops.api.exception;

public class InventoryNotFoundException extends RuntimeException {

    public InventoryNotFoundException(String itemId) {
        super("Inventory not found: " + itemId);
    }
}
