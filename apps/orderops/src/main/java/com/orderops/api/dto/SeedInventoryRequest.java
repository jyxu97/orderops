package com.orderops.api.dto;

import lombok.Data;

@Data
public class SeedInventoryRequest {
    private String itemId;
    private String itemName;
    private int quantity;
}
