package com.orderops.api.service;

import com.orderops.api.repository.OutboxRepository;
import com.orderops.shared.model.OutboxRecord;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sends the fulfillment messages that checkout committed but did not manage to enqueue.
 *
 * <p>Checkout writes an outbox record inside the order's transaction, then sends inline and
 * deletes the record. This sweep exists for the cases where that second step never completed:
 * SQS was unavailable, the credentials were wrong, or the process died between the commit and
 * the send. Those orders hold reserved stock and would otherwise sit in INVENTORY_RESERVED
 * forever with nothing to move them.
 *
 * <p>Why an outbox rather than sweeping orders by status: a status sweep cannot tell "never
 * enqueued" from "enqueued, worker is behind". During a worker outage every backlogged order
 * looks stale, so a status sweep would re-enqueue all of them and double the queue at the worst
 * possible moment. An outbox record is deleted the moment SQS accepts the message, so a
 * backlogged-but-sent order is invisible here no matter how long the worker takes.
 *
 * <p>Delivery is at-least-once: a crash after the send but before the delete resends the
 * message. That is safe because the fulfillment worker skips orders already in a terminal state
 * and every transition is conditioned on the status it read.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mode", havingValue = "api")
public class OutboxPublisher {

    private final OutboxRepository outboxRepository;
    private final SqsPublisher sqsPublisher;
    private final MeterRegistry meterRegistry;

    @Value("${outbox.batch-size:25}")
    private int batchSize;

    /**
     * Runs on a delay long enough that a record written by an in-progress checkout is normally
     * gone before the sweep sees it. Picking up such a record early is harmless — it only means
     * one duplicate SQS message — but there is no reason to race the request path for it.
     */
    @Scheduled(
        initialDelayString = "${outbox.poll-interval-ms:15000}",
        fixedDelayString = "${outbox.poll-interval-ms:15000}")
    public void publishPending() {
        List<OutboxRecord> pending;
        try {
            pending = outboxRepository.findPending(batchSize);
        } catch (RuntimeException e) {
            log.warn("Could not read the outbox: {}", e.getMessage());
            return;
        }

        if (pending.isEmpty()) {
            return;
        }

        log.info("Outbox holds {} unsent message(s); publishing", pending.size());
        for (OutboxRecord record : pending) {
            try {
                sqsPublisher.send(record.getPayload());
                // Only after SQS accepted it. Deleting first would turn a send failure into a
                // lost order, which is the failure this component exists to prevent.
                outboxRepository.markSent(record.getOrderId());
                meterRegistry.counter("outbox.published").increment();
                log.info("Recovered order {} from the outbox", record.getOrderId());
            } catch (RuntimeException e) {
                // Left in place for the next sweep. One record failing must not stop the rest:
                // a single malformed or unroutable message would otherwise block every order
                // behind it.
                meterRegistry.counter("outbox.publish_failed").increment();
                log.warn("Outbox publish failed for order {}: {}", record.getOrderId(), e.getMessage());
            }
        }
    }
}
