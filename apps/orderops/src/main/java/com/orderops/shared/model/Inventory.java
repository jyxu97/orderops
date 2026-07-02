package com.orderops.shared.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class Inventory {
    String itemId;
    int totalQuantity;
    int availableQuantity;
    int reservedQuantity;
    long version;
}
