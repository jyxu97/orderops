package com.orderops.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderops.api.repository.DynamoDbLocalProcess;
import com.orderops.api.repository.OutboxRepository;
import com.orderops.api.service.OutboxPublisher;
import com.orderops.api.service.SqsPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Covers the gap the outbox closes: an order can commit while its fulfillment message fails to
 * reach SQS, and nothing else in the system would ever enqueue it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutboxRecoveryTest {

    private static final DynamoDbLocalProcess DYNAMO;
    static {
        try {
            DYNAMO = DynamoDbLocalProcess.start();
            Runtime.getRuntime().addShutdownHook(new Thread(DYNAMO::close));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("dynamodb.endpoint", DYNAMO::endpoint);
        registry.add("aws.region", () -> "us-west-2");
    }

    @MockBean
    StringRedisTemplate redisTemplate;
    @MockBean
    SqsPublisher sqsPublisher;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private OutboxRepository outboxRepository;
    @Autowired
    private OutboxPublisher outboxPublisher;

    private String itemId;

    @BeforeEach
    void setUp() throws Exception {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(ops);
        Mockito.when(ops.get(anyString())).thenReturn(null);

        itemId = "outbox-item-" + UUID.randomUUID();
        mockMvc.perform(post("/api/v1/inventory/seed")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"itemId": "%s", "quantity": 20, "unitPrice": 3.00}
                    """.formatted(itemId)))
            .andExpect(status().isCreated());
    }

    private String createOrder(String idempotencyKey) throws Exception {
        var request = post("/api/v1/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"customerId": "outbox-customer", "items": [{"itemId": "%s", "quantity": 1}]}
                """.formatted(itemId));
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        String body = mockMvc.perform(request)
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("orderId").asText();
    }

    @Test
    void successfulEnqueue_leavesNothingInTheOutbox() throws Exception {
        String orderId = createOrder(null);

        // The record is written in the transaction and removed once SQS accepts the message, so
        // a healthy checkout leaves the table empty — which is what keeps the sweep cheap.
        assertTrue(outboxRepository.findPending(25).stream()
            .noneMatch(r -> r.getOrderId().equals(orderId)));
        Mockito.verify(sqsPublisher).send(SqsPublisher.orderCreatedPayload(orderId));
    }

    @Test
    void enqueueFailure_stillCreatesTheOrderAndLeavesTheObligation() throws Exception {
        Mockito.doThrow(new RuntimeException("SQS unavailable"))
            .when(sqsPublisher).send(anyString());

        // The order committed and is holding stock, so reporting failure to the client would be
        // less accurate than reporting the order it actually created.
        String orderId = createOrder(null);

        assertTrue(outboxRepository.findPending(25).stream()
            .anyMatch(r -> r.getOrderId().equals(orderId)),
            "a failed send must leave the obligation recorded");
    }

    @Test
    void outboxPublisher_recoversAStrandedOrder() throws Exception {
        Mockito.doThrow(new RuntimeException("SQS unavailable"))
            .when(sqsPublisher).send(anyString());
        String orderId = createOrder(null);
        Mockito.reset(sqsPublisher);   // SQS comes back

        outboxPublisher.publishPending();

        Mockito.verify(sqsPublisher).send(SqsPublisher.orderCreatedPayload(orderId));
        assertTrue(outboxRepository.findPending(25).stream()
            .noneMatch(r -> r.getOrderId().equals(orderId)),
            "a recovered order must not be published again on the next sweep");
    }

    @Test
    void outboxPublisher_leavesRecordsItCouldNotSend() throws Exception {
        Mockito.doThrow(new RuntimeException("SQS unavailable"))
            .when(sqsPublisher).send(anyString());
        String orderId = createOrder(null);

        outboxPublisher.publishPending();   // still failing

        assertTrue(outboxRepository.findPending(25).stream()
            .anyMatch(r -> r.getOrderId().equals(orderId)),
            "the record must survive a failed sweep, or the order is lost");
    }

    @Test
    void outboxPublisher_oneBadRecordDoesNotBlockTheRest() throws Exception {
        Mockito.doThrow(new RuntimeException("SQS unavailable"))
            .when(sqsPublisher).send(anyString());
        String first  = createOrder(null);
        String second = createOrder(null);

        // Only the first order's payload keeps failing.
        Mockito.reset(sqsPublisher);
        Mockito.doThrow(new RuntimeException("permanently unroutable"))
            .when(sqsPublisher).send(SqsPublisher.orderCreatedPayload(first));

        outboxPublisher.publishPending();

        // Without per-record isolation, one unroutable message would block every order behind it.
        Mockito.verify(sqsPublisher).send(SqsPublisher.orderCreatedPayload(second));
        assertTrue(outboxRepository.findPending(25).stream()
            .noneMatch(r -> r.getOrderId().equals(second)));
        assertTrue(outboxRepository.findPending(25).stream()
            .anyMatch(r -> r.getOrderId().equals(first)));
    }

    @Test
    void idempotentReplayAfterAFailedEnqueue_doesNotStrandTheOrder() throws Exception {
        // The failure mode the outbox exists for. Before it, a send failure returned 500, the
        // client retried with the same key, the transaction's idempotency condition returned
        // the original order with 200 — and the retry path never reached the enqueue, so the
        // order stayed in INVENTORY_RESERVED forever while the client saw success.
        Mockito.doThrow(new RuntimeException("SQS unavailable"))
            .when(sqsPublisher).send(anyString());
        String key = "outbox-replay-" + UUID.randomUUID();
        String orderId = createOrder(key);

        // The retry replays and returns the same order.
        String replayBody = mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content("""
                    {"customerId": "outbox-customer", "items": [{"itemId": "%s", "quantity": 1}]}
                    """.formatted(itemId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.replayed").value(true))
            .andReturn().getResponse().getContentAsString();
        assertEquals(orderId, objectMapper.readTree(replayBody).get("orderId").asText());

        // The obligation is still on record, so recovery no longer depends on the client.
        Mockito.reset(sqsPublisher);
        outboxPublisher.publishPending();
        Mockito.verify(sqsPublisher).send(SqsPublisher.orderCreatedPayload(orderId));
    }

    @Test
    void rolledBackTransaction_recordsNoObligation() throws Exception {
        String soldOut = "outbox-soldout-" + UUID.randomUUID();
        mockMvc.perform(post("/api/v1/inventory/seed")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"itemId": "%s", "quantity": 0, "unitPrice": 1.00}
                    """.formatted(soldOut)))
            .andExpect(status().isCreated());
        int before = outboxRepository.findPending(25).size();

        mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"customerId": "outbox-customer", "items": [{"itemId": "%s", "quantity": 1}]}
                    """.formatted(soldOut)))
            .andExpect(status().isConflict());

        // The outbox write is in the same transaction, so a rejected order cannot leave an
        // obligation behind to enqueue an order that does not exist.
        assertEquals(before, outboxRepository.findPending(25).size());
    }
}
