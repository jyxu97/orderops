package com.orderops.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderops.api.repository.DynamoDbLocalProcess;
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

import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class OrderControllerTest {

    // Static initializer runs at class load time — before @DynamicPropertySource is called.
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

    // Mock Redis so the Spring context loads without a real Redis connection
    @MockBean
    StringRedisTemplate redisTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String ITEM_ID = "widget-ctrl-test";

    @BeforeEach
    void setUp() throws Exception {
        // Stub Redis to simulate an empty cache (all gets return null → DynamoDB fallback)
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        Mockito.when(redisTemplate.opsForValue()).thenReturn(ops);
        Mockito.when(ops.get(anyString())).thenReturn(null);

        // Reset stock to 10 before each test
        mockMvc.perform(post("/inventory/seed")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"itemId": "%s", "quantity": 10}
                """.formatted(ITEM_ID)));
    }

    @Test
    void postOrders_success_returns201() throws Exception {
        String body = """
            {
              "customerId": "customer-1",
              "items": [{"itemId": "%s", "quantity": 2}]
            }
            """.formatted(ITEM_ID);

        mockMvc.perform(post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.orderId").isNotEmpty())
            .andExpect(jsonPath("$.status").value("INVENTORY_RESERVED"));
    }

    @Test
    void postOrders_insufficientInventory_returns409() throws Exception {
        String body = """
            {
              "customerId": "customer-2",
              "items": [{"itemId": "%s", "quantity": 999}]
            }
            """.formatted(ITEM_ID);

        mockMvc.perform(post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void getOrder_existingOrder_returns200() throws Exception {
        String createBody = """
            {
              "customerId": "customer-3",
              "items": [{"itemId": "%s", "quantity": 1}]
            }
            """.formatted(ITEM_ID);

        String createResult = mockMvc.perform(post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String orderId = objectMapper.readTree(createResult).get("orderId").asText();

        mockMvc.perform(get("/orders/" + orderId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.orderId").value(orderId))
            .andExpect(jsonPath("$.customerId").value("customer-3"))
            .andExpect(jsonPath("$.status").value("INVENTORY_RESERVED"));
    }

    @Test
    void getOrder_nonExistent_returns404() throws Exception {
        mockMvc.perform(get("/orders/order-does-not-exist"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404));
    }
}
