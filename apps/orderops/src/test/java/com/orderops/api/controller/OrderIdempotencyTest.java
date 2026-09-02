package com.orderops.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderops.api.repository.DynamoDbLocalProcess;
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

import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for idempotency behaviour on POST /orders.
 *
 * Redis is mocked to always return a cache miss; idempotency is enforced
 * via the real DynamoDB IdempotencyRecords table (DynamoDB Local).
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderIdempotencyTest {

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
    static void props(DynamicPropertyRegistry registry) {
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

    private static final String ITEM_ID = "widget-idem-test";

    @BeforeEach
    void setUp() throws Exception {
        // Redis always misses → idempotency enforcement falls through to DynamoDB
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(ops);
        Mockito.when(ops.get(anyString())).thenReturn(null);

        // Seed fresh inventory before each test
        mockMvc.perform(post("/api/v1/inventory/seed")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"itemId": "%s", "quantity": 100}
                """.formatted(ITEM_ID)));
    }

    @Test
    void sameKeyAndBody_returnsSameOrder() throws Exception {
        String idempotencyKey = "idem-key-" + UUID.randomUUID();
        String body = """
            {
              "customerId": "customer-idem-1",
              "items": [{"itemId": "%s", "quantity": 1}]
            }
            """.formatted(ITEM_ID);

        // First request: creates the order
        String firstResponse = mockMvc.perform(post("/api/v1/orders")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.orderId").isNotEmpty())
            .andReturn().getResponse().getContentAsString();

        String firstOrderId = objectMapper.readTree(firstResponse).get("orderId").asText();

        // Second request with identical key and body: returns 200 with the same order
        mockMvc.perform(post("/api/v1/orders")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.orderId").value(firstOrderId))
            .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    void sameKeyDifferentBody_returns409() throws Exception {
        String idempotencyKey = "idem-key-conflict-" + UUID.randomUUID();

        String body1 = """
            {
              "customerId": "customer-idem-2",
              "items": [{"itemId": "%s", "quantity": 1}]
            }
            """.formatted(ITEM_ID);

        String body2 = """
            {
              "customerId": "customer-idem-2",
              "items": [{"itemId": "%s", "quantity": 2}]
            }
            """.formatted(ITEM_ID);

        // First request succeeds
        mockMvc.perform(post("/api/v1/orders")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body1))
            .andExpect(status().isCreated());

        // Second request with same key but different body → 409 Conflict
        mockMvc.perform(post("/api/v1/orders")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body2))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void noIdempotencyKey_createsTwoSeparateOrders() throws Exception {
        String body = """
            {
              "customerId": "customer-no-idem",
              "items": [{"itemId": "%s", "quantity": 1}]
            }
            """.formatted(ITEM_ID);

        String resp1 = mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String resp2 = mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String orderId1 = objectMapper.readTree(resp1).get("orderId").asText();
        String orderId2 = objectMapper.readTree(resp2).get("orderId").asText();

        assert !orderId1.equals(orderId2) : "Without idempotency key, each request must create a distinct order";
    }
}
