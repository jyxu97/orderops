package com.orderops.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderops.api.repository.DynamoDbLocalProcess;
import com.orderops.api.service.SqsPublisher;
import com.orderops.shared.state.OrderStatus;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end coverage of POST /orders/{id}/cancel: the transactional inventory release,
 * idempotency of a repeated cancel, and the guard against cancelling a mid-fulfillment order.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderCancellationTest {

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
    static void dynamoProperties(DynamicPropertyRegistry registry) {
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

    private String itemId;

    @BeforeEach
    void setUp() throws Exception {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(ops);
        Mockito.when(ops.get(anyString())).thenReturn(null);

        // A dedicated SKU per test keeps the inventory assertions independent.
        itemId = "cancel-item-" + UUID.randomUUID();
        mockMvc.perform(post("/api/v1/inventory/seed")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"itemId": "%s", "quantity": 10, "unitPrice": 12.50}
                    """.formatted(itemId)))
            .andExpect(status().isCreated());
    }

    private String createOrder(int quantity) throws Exception {
        String result = mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"customerId": "cancel-customer", "items": [{"itemId": "%s", "quantity": %d}]}
                    """.formatted(itemId, quantity)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(result).get("orderId").asText();
    }

    @Test
    void cancel_releasesReservedInventory() throws Exception {
        String orderId = createOrder(3);

        mockMvc.perform(get("/api/v1/inventory/" + itemId))
            .andExpect(jsonPath("$.availableQuantity").value(7))
            .andExpect(jsonPath("$.reservedQuantity").value(3));

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.cancellable").value(false));

        // Stock is back in the catalog and no longer held in reserve.
        mockMvc.perform(get("/api/v1/inventory/" + itemId))
            .andExpect(jsonPath("$.availableQuantity").value(10))
            .andExpect(jsonPath("$.reservedQuantity").value(0));
    }

    @Test
    void cancel_repeatedCall_isIdempotentAndDoesNotReleaseTwice() throws Exception {
        String orderId = createOrder(4);

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));

        // A client retrying after a timeout must not push availableQuantity above the total.
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/v1/inventory/" + itemId))
            .andExpect(jsonPath("$.availableQuantity").value(10))
            .andExpect(jsonPath("$.reservedQuantity").value(0));
    }

    @Test
    void cancel_multiItemOrder_releasesEveryLineItem() throws Exception {
        String secondItem = "cancel-item-" + UUID.randomUUID();
        mockMvc.perform(post("/api/v1/inventory/seed")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"itemId": "%s", "quantity": 5, "unitPrice": 3.00}
                    """.formatted(secondItem)))
            .andExpect(status().isCreated());

        String result = mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"customerId": "cancel-customer", "items": [
                       {"itemId": "%s", "quantity": 2},
                       {"itemId": "%s", "quantity": 1}
                    ]}
                    """.formatted(itemId, secondItem)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String orderId = objectMapper.readTree(result).get("orderId").asText();

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/inventory/" + itemId))
            .andExpect(jsonPath("$.availableQuantity").value(10));
        mockMvc.perform(get("/api/v1/inventory/" + secondItem))
            .andExpect(jsonPath("$.availableQuantity").value(5));
    }

    @Test
    void cancel_orderInPayment_returns409AndKeepsInventoryReserved() throws Exception {
        String orderId = createOrder(2);

        // Simulate the worker having picked the order up.
        advanceStatus(orderId, OrderStatus.PAYMENT_PROCESSING, 1L);

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.message")
                .value(org.hamcrest.Matchers.containsString("PAYMENT_PROCESSING")));

        // A rejected cancel must not have released anything.
        mockMvc.perform(get("/api/v1/inventory/" + itemId))
            .andExpect(jsonPath("$.availableQuantity").value(8))
            .andExpect(jsonPath("$.reservedQuantity").value(2));
    }

    @Test
    void cancel_orderInManualReview_isAllowedAsOperatorResolution() throws Exception {
        String orderId = createOrder(2);
        advanceStatus(orderId, OrderStatus.PAYMENT_PROCESSING, 1L);
        advanceStatus(orderId, OrderStatus.FAILED, 2L);
        advanceStatus(orderId, OrderStatus.NEEDS_MANUAL_REVIEW, 3L);

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/v1/inventory/" + itemId))
            .andExpect(jsonPath("$.availableQuantity").value(10));
    }

    @Test
    void cancel_unknownOrder_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/orders/does-not-exist/cancel"))
            .andExpect(status().isNotFound());
    }

    @Test
    void getOrder_exposesPricingSnapshotAndCancellability() throws Exception {
        String orderId = createOrder(2);

        mockMvc.perform(get("/api/v1/orders/" + orderId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalAmount").value(25.00))
            .andExpect(jsonPath("$.items[0].unitPrice").value(12.50))
            .andExpect(jsonPath("$.items[0].lineTotal").value(25.00))
            .andExpect(jsonPath("$.cancellable").value(true));
    }

    @Test
    void createOrder_unknownItem_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"customerId": "c", "items": [{"itemId": "no-such-sku", "quantity": 1}]}
                    """))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message")
                .value(org.hamcrest.Matchers.containsString("no-such-sku")));
    }

    /** Moves an order forward the way the fulfillment worker would. */
    private void advanceStatus(String orderId, OrderStatus status, long expectedVersion) {
        orderRepository.updateStatus(orderId, status, expectedVersion);
    }

    @Autowired
    private com.orderops.api.repository.OrderRepository orderRepository;
}
