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

    private static final int BASE_BACKOFF_SECONDS = 30;
    private static final int MAX_BACKOFF_SECONDS  = 300; // 5 min

    private final SqsClient sqsClient;
    private final OrderFulfillmentService fulfillmentService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Value("${sqs.fulfillment-queue-url}")
    private String queueUrl;

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

    /** Backoff: 30s × 2^(attempt-1), capped at MAX_BACKOFF_SECONDS. */
    private int computeBackoff(int attempt) {
        int backoff = BASE_BACKOFF_SECONDS * (1 << Math.max(0, attempt - 1));
        return Math.min(backoff, MAX_BACKOFF_SECONDS);
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
