package com.orderops.worker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulates payment processing.
 *
 * <p>Behaviour is controlled by {@code simulator.payment.failure-mode}:
 * <ul>
 *   <li>{@code NONE} (default) — always succeeds; uses {@code simulator.failure-rate} for
 *       random transient faults</li>
 *   <li>{@code TRANSIENT} — throws for the first {@code transientFailsRemaining} calls, then
 *       succeeds. Defaults to {@link Integer#MAX_VALUE} (always fail) so existing behaviour is
 *       preserved unless overridden in tests via {@code ReflectionTestUtils}.</li>
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

    /** Number of transient failures remaining before the simulator recovers. */
    private AtomicInteger transientFailsRemaining = new AtomicInteger(Integer.MAX_VALUE);

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
            case "TRANSIENT" -> {
                if (transientFailsRemaining.getAndDecrement() > 0) {
                    throw new RuntimeException("Transient payment failure orderId=" + orderId);
                }
                log.info("Transient resolved, payment authorized orderId={}", orderId);
                yield true;
            }
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