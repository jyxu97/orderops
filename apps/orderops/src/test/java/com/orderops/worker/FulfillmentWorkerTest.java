package com.orderops.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;

/**
 * Covers the SQS poll loop: acknowledgement, retry backoff, and at-least-once delivery.
 *
 * {@link OrderFulfillmentServiceTest} covers what happens to an order; this covers what happens
 * to the message. They are separate concerns and the message half was previously untested — the
 * backoff schedule that the project's retry claim rests on had no assertion against it.
 */
class FulfillmentWorkerTest {

    private static final String QUEUE_URL = "http://localhost:4566/000000000000/order-fulfillment-queue";
    private static final int BASE_BACKOFF = 30;
    private static final int MAX_BACKOFF = 300;

    private SqsClient sqsClient;
    private OrderFulfillmentService fulfillmentService;
    private SimpleMeterRegistry meterRegistry;
    private FulfillmentWorker worker;

    @BeforeEach
    void setUp() {
        sqsClient = Mockito.mock(SqsClient.class);
        fulfillmentService = Mockito.mock(OrderFulfillmentService.class);
        meterRegistry = new SimpleMeterRegistry();

        worker = new FulfillmentWorker(sqsClient, fulfillmentService, new ObjectMapper(), meterRegistry);
        ReflectionTestUtils.setField(worker, "queueUrl", QUEUE_URL);
        ReflectionTestUtils.setField(worker, "baseBackoffSeconds", BASE_BACKOFF);
        ReflectionTestUtils.setField(worker, "maxBackoffSeconds", MAX_BACKOFF);
    }

    /** Queues one message with the given receive count for the next poll. */
    private void givenMessage(String orderId, int receiveCount) {
        Message message = Message.builder()
            .body("{\"orderId\":\"" + orderId + "\"}")
            .receiptHandle("receipt-" + orderId)
            .attributesWithStrings(Map.of("ApproximateReceiveCount", String.valueOf(receiveCount)))
            .build();
        Mockito.when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
            .thenReturn(ReceiveMessageResponse.builder().messages(message).build());
    }

    private int capturedBackoff() {
        ArgumentCaptor<ChangeMessageVisibilityRequest> captor =
            ArgumentCaptor.forClass(ChangeMessageVisibilityRequest.class);
        Mockito.verify(sqsClient).changeMessageVisibility(captor.capture());
        return captor.getValue().visibilityTimeout();
    }

    @Test
    void successfulFulfillment_deletesTheMessage() {
        givenMessage("order-1", 1);

        worker.poll();

        Mockito.verify(fulfillmentService).fulfill("order-1");
        Mockito.verify(sqsClient).deleteMessage(any(DeleteMessageRequest.class));
        Mockito.verify(sqsClient, Mockito.never()).changeMessageVisibility(any(ChangeMessageVisibilityRequest.class));
        assertEquals(1.0, meterRegistry.counter("worker.messages.processed").count());
    }

    @Test
    void failedFulfillment_leavesTheMessageForRedelivery() {
        givenMessage("order-2", 1);
        Mockito.doThrow(new RuntimeException("downstream timeout"))
            .when(fulfillmentService).fulfill("order-2");

        worker.poll();

        // Not deleting is what makes SQS redeliver. Deleting on failure would silently drop the
        // order, and no retry or DLQ could ever see it.
        Mockito.verify(sqsClient, Mockito.never()).deleteMessage(any(DeleteMessageRequest.class));
        assertEquals(1.0, meterRegistry.counter("worker.messages.failed").count());
    }

    @Test
    void backoffDoublesWithEachRedelivery() {
        for (int attempt = 1; attempt <= 4; attempt++) {
            setUp();
            givenMessage("order-b", attempt);
            Mockito.doThrow(new RuntimeException("still failing"))
                .when(fulfillmentService).fulfill("order-b");

            worker.poll();

            int expected = Math.min(BASE_BACKOFF * (1 << (attempt - 1)), MAX_BACKOFF);
            assertEquals(expected, capturedBackoff(), "attempt " + attempt);
        }
    }

    @Test
    void backoffIsCappedAtTheConfiguredMaximum() {
        givenMessage("order-c", 8);
        Mockito.doThrow(new RuntimeException("still failing")).when(fulfillmentService).fulfill("order-c");

        worker.poll();

        assertEquals(MAX_BACKOFF, capturedBackoff());
    }

    @Test
    void hugeReceiveCount_stillYieldsAValidTimeout() {
        // Regression: computed in int, `base << (attempt-1)` overflows past the high twenties
        // and goes negative (30 << 30 == -2147483648). SQS rejects a negative visibility
        // timeout, so the extension fails and the message redelivers immediately — the exact
        // opposite of backing off, and only at the point where backing off matters most.
        givenMessage("order-d", 31);
        Mockito.doThrow(new RuntimeException("still failing")).when(fulfillmentService).fulfill("order-d");

        worker.poll();

        int backoff = capturedBackoff();
        assertTrue(backoff > 0, "visibility timeout must be positive, got " + backoff);
        assertEquals(MAX_BACKOFF, backoff);
    }

    @Test
    void receiveCountAtIntegerMaximum_doesNotOverflow() {
        givenMessage("order-e", Integer.MAX_VALUE);
        Mockito.doThrow(new RuntimeException("still failing")).when(fulfillmentService).fulfill("order-e");

        worker.poll();

        assertEquals(MAX_BACKOFF, capturedBackoff());
    }

    @Test
    void duplicateDeliveryOfTheSameMessage_isHandledIdempotently() {
        // SQS is at-least-once: the same message can arrive twice, including after a worker
        // crashed between processing and acknowledging. The worker delegates the protection to
        // the service, which skips terminal orders — so both deliveries must be acknowledged
        // rather than one being treated as an error.
        givenMessage("order-dup", 1);

        worker.poll();
        worker.poll();

        Mockito.verify(fulfillmentService, Mockito.times(2)).fulfill("order-dup");
        Mockito.verify(sqsClient, Mockito.times(2)).deleteMessage(any(DeleteMessageRequest.class));
        assertEquals(2.0, meterRegistry.counter("worker.messages.processed").count());
    }

    @Test
    void malformedBody_isCountedAsAFailureWithoutCrashingTheLoop() {
        Message message = Message.builder()
            .body("not json")
            .receiptHandle("receipt-bad")
            .attributesWithStrings(Map.of("ApproximateReceiveCount", "1"))
            .build();
        Mockito.when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
            .thenReturn(ReceiveMessageResponse.builder().messages(message).build());

        assertDoesNotThrow(() -> worker.poll());

        Mockito.verifyNoInteractions(fulfillmentService);
        assertEquals(1.0, meterRegistry.counter("worker.messages.failed").count());
    }

    @Test
    void aFailingVisibilityExtension_doesNotStopTheBatch() {
        Message first = Message.builder().body("{\"orderId\":\"order-f1\"}")
            .receiptHandle("r1").attributesWithStrings(Map.of("ApproximateReceiveCount", "1")).build();
        Message second = Message.builder().body("{\"orderId\":\"order-f2\"}")
            .receiptHandle("r2").attributesWithStrings(Map.of("ApproximateReceiveCount", "1")).build();
        Mockito.when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
            .thenReturn(ReceiveMessageResponse.builder().messages(first, second).build());
        Mockito.doThrow(new RuntimeException("fail")).when(fulfillmentService).fulfill("order-f1");
        Mockito.doThrow(new RuntimeException("sqs unavailable"))
            .when(sqsClient).changeMessageVisibility(any(ChangeMessageVisibilityRequest.class));

        assertDoesNotThrow(() -> worker.poll());

        // The second message must still be processed: one message's backoff failing is not a
        // reason to abandon the rest of the batch.
        Mockito.verify(fulfillmentService).fulfill("order-f2");
    }

    @Test
    void emptyPoll_doesNothing() {
        Mockito.when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
            .thenReturn(ReceiveMessageResponse.builder().messages(java.util.List.of()).build());

        worker.poll();

        Mockito.verifyNoInteractions(fulfillmentService);
        Mockito.verify(sqsClient, Mockito.never()).deleteMessage(any(DeleteMessageRequest.class));
    }
}
