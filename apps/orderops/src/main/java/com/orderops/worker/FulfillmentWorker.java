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
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * SQS long-poll consumer.
 *
 * <p>Active only when {@code app.mode=worker}. Polls the fulfillment queue every second,
 * processes each message with {@link OrderFulfillmentService}, then deletes it.
 *
 * <p>On any exception the message is NOT deleted, so SQS redelivers it after the
 * visibility timeout. After {@code maxReceiveCount} retries SQS routes it to the DLQ.
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

    @Scheduled(fixedDelay = 1000)
    public void poll() {
        var response = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
            .queueUrl(queueUrl)
            .maxNumberOfMessages(10)
            .waitTimeSeconds(5)
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
                log.error("Failed to process message orderId={}, will redeliver", orderId, e);
                meterRegistry.counter("worker.messages.failed").increment();
                // Do not delete — SQS visibility timeout will expire and redeliver
            }
        }
    }

    private void deleteMessage(String receiptHandle) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
            .queueUrl(queueUrl)
            .receiptHandle(receiptHandle)
            .build());
    }
}