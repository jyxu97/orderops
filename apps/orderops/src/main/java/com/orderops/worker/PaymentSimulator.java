package com.orderops.worker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Simulates payment processing.
 *
 * <p>Behaviour is controlled by {@code simulator.payment.failure-mode}:
 * <ul>
 *   <li>{@code NONE} (default) — always succeeds; uses {@code simulator.failure-rate} for
 *       random transient faults</li>
 *   <li>{@code TRANSIENT} — always throws, triggering SQS redelivery and eventual DLQ routing</li>
 *   <li>{@code PERMANENT} — always returns {@code false}, sending the order to FAILED →
 *       NEEDS_MANUAL_REVIEW</li>
 * </ul>
 */
@Slf4j
@Component
public class PaymentSimulator {

    @Value("${simulator.payment.failure-mode:NONE}")
    private String failureMode;

    @Value("${simulator.failure-rate:0.0}")
    private double failureRate;

    /**
     * @return {@code true} if payment succeeded, {@code false} for a permanent decline
     * @throws RuntimeException for a transient failure (SQS will redeliver the message)
     */
    public boolean process(String orderId) {
        log.info("Processing payment orderId={} mode={}", orderId, failureMode);
        return switch (failureMode.toUpperCase()) {
            case "PERMANENT" -> {
                log.warn("Payment permanently declined orderId={}", orderId);
                yield false;
            }
            case "TRANSIENT" ->
                throw new RuntimeException("Transient payment failure orderId=" + orderId);
            default -> {
                if (failureRate > 0 && Math.random() < failureRate) {
                    throw new RuntimeException("Random payment failure orderId=" + orderId);
                }
                log.info("Payment authorized orderId={}", orderId);
                yield true;
            }
        };
    }
}
