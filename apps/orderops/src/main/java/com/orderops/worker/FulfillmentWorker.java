package com.orderops.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * SQS long-poll consumer.
 *
 * <p>Active only when {@code app.mode=worker}. Polls the fulfillment queue every second,
 * processes each message with {@link OrderFulfillmentService}, then deletes it on success.
 *
 * <p>On transient failure the message is NOT deleted. Instead, the visibility timeout is
 * extended with exponential backoff (30 s → 60 s → 120 s, capped at 5 min) so the queue
 * is not flooded with immediate redeliveries. After {@code maxReceiveCount} failed attempts
 * SQS routes the message to the DLQ.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mode", havingValue = "worker")
public class FulfillmentWorker {

    private final SqsClient sqsClient;
    private final OrderFulfillmentService fulfillmentService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Value("${sqs.fulfillment-queue-url}")
    private String queueUrl;

    @Value("${worker.backoff.base-seconds:30}")
    private int baseBackoffSeconds;

    @Value("${worker.backoff.max-seconds:300}")
    private int maxBackoffSeconds;

    @Scheduled(fixedDelay = 1000)
    public void poll() {
        var response = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
            .queueUrl(queueUrl)
            .maxNumberOfMessages(10)
            .waitTimeSeconds(5)
            .attributeNamesWithStrings("ApproximateReceiveCount")
            .build());

        for (Message message : response.messages()) {
            String orderId = null;
            try {
                orderId = objectMapper.readTree(message.body()).get("orderId").asText();
                log.info("Processing fulfillment message orderId={}", orderId);
                fulfillmentService.fulfill(orderId);
                deleteMessage(message.receiptHandle());
                meterRegistry.counter("worker.messages.processed").increment();
            } catch (Exception e) {
                int attempt = parseReceiveCount(message);
                int backoff  = computeBackoff(attempt);
                log.warn("Transient failure orderId={} attempt={}, backing off {}s before redelivery",
                    orderId, attempt, backoff, e);
                applyBackoff(message.receiptHandle(), backoff);
                meterRegistry.counter("worker.messages.failed").increment();
            }
        }
    }

    /**
     * Backoff: baseBackoffSeconds × 2^(attempt-1), capped at maxBackoffSeconds.
     *
     * <p>Computed in {@code long} and with the exponent clamped. In {@code int} arithmetic the
     * shift overflows once the receive count passes the high twenties and the result goes
     * negative — {@code base=30, attempt=31} yields -2147483648 — which SQS rejects, so the
     * visibility extension silently fails and the message redelivers immediately instead of
     * backing off. A redrive policy with a high {@code maxReceiveCount}, or a message redriven
     * more than once, is enough to reach that range.
     */
    private int computeBackoff(int attempt) {
        int exponent = Math.min(Math.max(0, attempt - 1), Integer.SIZE - 2);
        long backoff = (long) baseBackoffSeconds << exponent;
        return (int) Math.min(backoff, maxBackoffSeconds);
    }

    private int parseReceiveCount(Message message) {
        String raw = message.attributesAsStrings()
            .getOrDefault("ApproximateReceiveCount", "1");
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private void applyBackoff(String receiptHandle, int visibilityTimeoutSeconds) {
        try {
            sqsClient.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(receiptHandle)
                .visibilityTimeout(visibilityTimeoutSeconds)
                .build());
        } catch (Exception e) {
            log.warn("Failed to apply backoff, message will redeliver on default timeout", e);
        }
    }

    private void deleteMessage(String receiptHandle) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
            .queueUrl(queueUrl)
            .receiptHandle(receiptHandle)
            .build());
    }
}
