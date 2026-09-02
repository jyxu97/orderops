package com.orderops.api.controller;

import com.orderops.api.dto.FailedOrderResponse;
import com.orderops.api.dto.OpsOverviewResponse;
import com.orderops.api.dto.OrderSummaryResponse;
import com.orderops.api.dto.QueueHealthResponse;
import com.orderops.api.service.OperationsService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only operations endpoints backing the dashboard.
 *
 * <p>Deliberately small: the dashboard needs an overview, a failure list and queue depth, not
 * a generic query API. Per-status pagination is already served by {@code GET /api/v1/orders}.
 */
@RestController
@RequestMapping("/api/v1/ops")
@RequiredArgsConstructor
public class OperationsController {

    private final OperationsService operationsService;

    /** Status counts, recent orders and queue depth in one round trip. */
    @GetMapping("/overview")
    public OpsOverviewResponse overview(
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int recentLimit
    ) {
        return operationsService.overview(recentLimit);
    }

    /** Most recently updated orders across every status. */
    @GetMapping("/orders")
    public List<OrderSummaryResponse> recentOrders(
        @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit
    ) {
        return operationsService.recentOrders(limit);
    }

    /** Failed orders joined with the reason each one failed. */
    @GetMapping("/failures")
    public List<FailedOrderResponse> failures(
        @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit
    ) {
        return operationsService.failures(limit);
    }

    @GetMapping("/queue-health")
    public QueueHealthResponse queueHealth() {
        return operationsService.queueHealth();
    }
}
