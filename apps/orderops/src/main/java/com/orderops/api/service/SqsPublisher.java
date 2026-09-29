package com.orderops.api.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Slf4j
@Service
@RequiredArgsConstructor
public class SqsPublisher {

    private final SqsClient sqsClient;

    @Value("${sqs.fulfillment-queue-url}")
    private String fulfillmentQueueUrl;

    /**
     * The message body for a newly created order.
     *
     * <p>Built here and stored in the outbox so a retry sends the identical bytes rather than
     * re-deriving them from an order that may have moved on since.
     */
    public static String orderCreatedPayload(String orderId) {
        return "{\"orderId\":\"" + orderId + "\"}";
    }

    /** Sends an already-built payload. Throws on failure; the caller decides what that means. */
    public void send(String payload) {
        sqsClient.sendMessage(SendMessageRequest.builder()
            .queueUrl(fulfillmentQueueUrl)
            .messageBody(payload)
            .build());
        log.info("Published to fulfillment queue: {}", payload);
    }
}