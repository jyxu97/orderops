package com.orderops.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class SeedInventoryRequest {

    @NotBlank(message = "itemId is required")
    @Size(max = 128, message = "itemId must be at most 128 characters")
    private String itemId;

    @Size(max = 256, message = "itemName must be at most 256 characters")
    private String itemName;

    @Min(value = 0, message = "quantity must not be negative")
    private int quantity;

    /** Optional so that pre-pricing seed scripts keep working; absent means free. */
    @PositiveOrZero(message = "unitPrice must not be negative")
    @Digits(integer = 10, fraction = 2, message = "unitPrice must have at most 2 decimal places")
    @DecimalMax(value = "1000000.00", message = "unitPrice must be at most 1000000.00")
    private BigDecimal unitPrice;
}
