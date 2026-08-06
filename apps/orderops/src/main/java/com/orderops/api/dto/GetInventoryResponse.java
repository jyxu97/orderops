package com.orderops.api.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class GetInventoryResponse {
    String itemId;
    String itemName;
    int totalQuantity;
    int availableQuantity;
    int reservedQuantity;
    long version;
}
