package com.orderops.shared.model;

import com.orderops.shared.state.OrderStatus;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.List;

@Value
@Builder
public class Order {
    String orderId;
    String customerId;
    List<OrderItem> items;
    OrderStatus status;
    /** Sum of every line total, snapshotted at checkout. */
    BigDecimal totalAmount;
    long version;
    String createdAt;
    String updatedAt;

    @Value
    @Builder
    public static class OrderItem {
        String itemId;
        int quantity;
        /**
         * Catalog price at the moment the order was placed.
         *
         * <p>Snapshotted rather than looked up on read so that a later catalog price change
         * cannot silently rewrite the value of an existing order.
         */
        BigDecimal unitPrice;

        public BigDecimal lineTotal() {
            return unitPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }
}
