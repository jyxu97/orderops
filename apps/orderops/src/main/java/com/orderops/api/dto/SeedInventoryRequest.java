package com.orderops.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SeedInventoryRequest {

    @NotBlank(message = "itemId is required")
    @Size(max = 128, message = "itemId must be at most 128 characters")
    private String itemId;

    @Size(max = 256, message = "itemName must be at most 256 characters")
    private String itemName;

    @Min(value = 0, message = "quantity must not be negative")
    private int quantity;
}
