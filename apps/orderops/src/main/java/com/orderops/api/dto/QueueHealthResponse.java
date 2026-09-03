package com.orderops.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

/**
 * Fulfillment queue and DLQ depth, for the operations dashboard's backlog widgets.
 *
 * <p>{@code available} is false when SQS could not be reached. The dashboard renders that as
 * "unknown" rather than failing the whole page — a broken metrics read should not hide the
 * order data next to it.
 *
 * <p>Message age is deliberately absent: {@code ApproximateAgeOfOldestMessage} is a CloudWatch
 * metric, not an SQS queue attribute, and the only way to read it from SQS itself is to receive
 * a message — which would advance its receive count and push it toward the DLQ. Age is alarmed
 * on in CloudWatch instead.
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QueueHealthResponse {

    boolean available;
    /** Why the reading is unavailable. Present only when {@code available} is false. */
    String unavailableReason;

    QueueStats queue;
    QueueStats deadLetterQueue;

    /**
     * The backlog figure the queue is judged against, echoed back so the dashboard can render
     * depth as a ratio without keeping its own copy of a server-side threshold.
     */
    Integer backlogThreshold;

    /**
     * Whether the backlog is within its configured thresholds. Null when {@code available}
     * is false, so a missing reading is never mistaken for a healthy one.
     */
    Boolean healthy;
    /** Threshold breaches behind an unhealthy verdict, e.g. "DLQ has 3 message(s)". */
    java.util.List<String> warnings;

    @Value
    @Builder
    public static class QueueStats {
        String queueName;
        int visibleMessages;
        int inFlightMessages;
        int delayedMessages;
    }
}
