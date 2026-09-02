package com.orderops.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreateOrderRequest {

    @NotBlank(message = "customerId is required")
    @Size(max = 128, message = "customerId must be at most 128 characters")
    private String customerId;

    // DynamoDB allows at most 100 actions per TransactWriteItems call, and the order Put plus
    // the idempotency Put occupy two of those slots.
    @NotEmpty(message = "items must contain at least one line item")
    @Size(max = 20, message = "an order may contain at most 20 line items")
    @Valid
    private List<OrderItemDto> items;

    @Data
    public static class OrderItemDto {

        @NotBlank(message = "itemId is required")
        @Size(max = 128, message = "itemId must be at most 128 characters")
        private String itemId;

        @Min(value = 1, message = "quantity must be at least 1")
        private int quantity;
    }
}
