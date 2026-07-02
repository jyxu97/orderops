package com.orderops.worker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulates shipment processing.
 *
 * <p>Same failure-mode semantics as {@link PaymentSimulator}, controlled by
 * {@code simulator.shipment.failure-mode}.
 */
@Slf4j
@Component
public class ShipmentSimulator {

    @Value("${simulator.shipment.failure-mode:NONE}")
    private String failureMode;

    @Value("${simulator.failure-rate:0.0}")
    private double failureRate;

    /** Number of transient failures remaining before the simulator recovers. */
    private AtomicInteger transientFailsRemaining = new AtomicInteger(Integer.MAX_VALUE);

    /**
     * @return {@code true} if shipment succeeded, {@code false} for a permanent failure
     * @throws RuntimeException for a transient failure (SQS will redeliver the message)
     */
    public boolean process(String orderId) {
        log.info("Processing shipment orderId={} mode={}", orderId, failureMode);
        return switch (failureMode.toUpperCase()) {
            case "PERMANENT" -> {
                log.warn("Shipment permanently failed orderId={}", orderId);
                yield false;
            }
            case "TRANSIENT" -> {
                if (transientFailsRemaining.getAndDecrement() > 0) {
                    throw new RuntimeException("Transient shipment failure orderId=" + orderId);
                }
                log.info("Transient resolved, shipment dispatched orderId={}", orderId);
                yield true;
            }
            default -> {
                if (failureRate > 0 && Math.random() < failureRate) {
                    throw new RuntimeException("Random shipment failure orderId=" + orderId);
                }
                log.info("Shipment dispatched orderId={}", orderId);
                yield true;
            }
        };
    }
}