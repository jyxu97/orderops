package com.orderops.shared.model;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class Inventory {
    String itemId;
    String itemName;
    /** Current catalog price per unit. Orders snapshot this value at checkout time. */
    BigDecimal unitPrice;
    int totalQuantity;
    int availableQuantity;
    int reservedQuantity;
    long version;
}
