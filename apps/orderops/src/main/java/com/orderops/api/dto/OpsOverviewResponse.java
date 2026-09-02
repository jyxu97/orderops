package com.orderops.api.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/** Everything the operations dashboard landing page needs, in one round trip. */
@Value
@Builder
public class OpsOverviewResponse {

    /** Order count per status name. Statuses with no orders are present with a count of 0. */
    Map<String, Integer> statusCounts;

    /**
     * True when any status count stopped at its page cap, meaning the totals are lower
     * bounds rather than exact figures.
     */
    boolean countsCapped;

    /** Most recently updated orders across every status. */
    List<OrderSummaryResponse> recentOrders;

    QueueHealthResponse queueHealth;

    String generatedAt;
}
