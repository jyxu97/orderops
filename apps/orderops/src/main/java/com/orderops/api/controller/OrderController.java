package com.orderops.api.controller;

import com.orderops.api.dto.CreateOrderRequest;
import com.orderops.api.dto.CreateOrderResponse;
import com.orderops.api.dto.GetOrderResponse;
import com.orderops.api.dto.OrderAuditEntryResponse;
import com.orderops.api.dto.OrderSummaryResponse;
import com.orderops.api.dto.PageResponse;
import com.orderops.api.service.OrderService;
import com.orderops.shared.state.OrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<CreateOrderResponse> createOrder(
        @Valid @RequestBody CreateOrderRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        CreateOrderResponse response = orderService.createOrder(request, idempotencyKey);
        HttpStatus status = response.isReplayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * Lists orders filtered by exactly one of {@code customerId} or {@code status}.
     *
     * <p>Both filters are backed by a GSI, so neither degrades into a table scan. Requiring
     * exactly one of them keeps that guarantee: an unfiltered list has no index to serve it.
     */
    @GetMapping
    public PageResponse<OrderSummaryResponse> listOrders(
        @RequestParam(required = false) String customerId,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
        @RequestParam(required = false) String cursor
    ) {
        boolean byCustomer = customerId != null && !customerId.isBlank();
        boolean byStatus   = status != null && !status.isBlank();

        if (byCustomer == byStatus) {
            throw new IllegalArgumentException(
                "Specify exactly one of 'customerId' or 'status'");
        }

        return byCustomer
            ? orderService.listOrdersByCustomer(customerId, limit, cursor)
            : orderService.listOrdersByStatus(parseStatus(status), limit, cursor);
    }

    @GetMapping("/{orderId}")
    public GetOrderResponse getOrder(@PathVariable String orderId) {
        return orderService.getOrder(orderId);
    }

    /**
     * The order's recorded state transitions, oldest first.
     *
     * <p>This is the timeline the order detail page renders, and the audit trail an operator
     * reads to see why an order failed.
     */
    @GetMapping("/{orderId}/audit")
    public List<OrderAuditEntryResponse> getOrderAudit(@PathVariable String orderId) {
        return orderService.getOrderAudit(orderId);
    }

    /**
     * Cancels an order and releases its reserved stock.
     *
     * <p>Returns 200 with the order's state on success and on a repeat call for an order that
     * is already cancelled; 409 when the order has moved too far through fulfillment to cancel.
     */
    @PostMapping("/{orderId}/cancel")
    public GetOrderResponse cancelOrder(@PathVariable String orderId) {
        return orderService.cancelOrder(orderId);
    }

    private static OrderStatus parseStatus(String raw) {
        try {
            return OrderStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown order status: " + raw);
        }
    }
}
