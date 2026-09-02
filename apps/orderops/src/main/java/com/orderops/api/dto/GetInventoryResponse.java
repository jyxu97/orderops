package com.orderops.api.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class GetInventoryResponse {
    String itemId;
    String itemName;
    BigDecimal unitPrice;
    int totalQuantity;
    int availableQuantity;
    int reservedQuantity;
    long version;
}
