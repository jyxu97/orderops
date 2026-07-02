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

    public void publishOrderCreated(String orderId) {
        String body = "{\"orderId\":\"" + orderId + "\"}";
        sqsClient.sendMessage(SendMessageRequest.builder()
            .queueUrl(fulfillmentQueueUrl)
            .messageBody(body)
            .build());
        log.info("Published order to fulfillment queue orderId={}", orderId);
    }
}