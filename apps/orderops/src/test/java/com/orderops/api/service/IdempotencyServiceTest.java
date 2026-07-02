package com.orderops.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderops.api.dto.CreateOrderRequest;
import com.orderops.api.dto.CreateOrderResponse;
import com.orderops.api.exception.IdempotencyConflictException;
import com.orderops.api.repository.IdempotencyRepository;
import com.orderops.shared.model.IdempotencyRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private IdempotencyRepository idempotencyRepository;

    @Spy
    private ObjectMapper objectMapper;

    @InjectMocks
    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        // lenient: hash-only tests don't interact with Redis so this stub would otherwise
        // trigger UnnecessaryStubbing in strict mode
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    // ------------------------------------------------------------------
    // findCachedResponse
    // ------------------------------------------------------------------

    @Test
    void findCachedResponse_redisHit_sameHash_returnsCachedOrder() throws Exception {
        String key = "key-1";
        String hash = "abc123";
        String json = buildRecordJson(key, hash, "order-1", "INVENTORY_RESERVED", "2026-01-01T00:00:00Z");

        when(valueOps.get("idem:" + key)).thenReturn(json);

        CreateOrderResponse result = idempotencyService.findCachedResponse(key, hash);

        assertNotNull(result);
        assertEquals("order-1", result.getOrderId());
        assertEquals("INVENTORY_RESERVED", result.getStatus());
    }

    @Test
    void findCachedResponse_redisHit_differentHash_throwsConflict() throws Exception {
        String key = "key-conflict";
        String storedJson = buildRecordJson(key, "hash-original", "order-x", "INVENTORY_RESERVED", "2026-01-01T00:00:00Z");

        when(valueOps.get("idem:" + key)).thenReturn(storedJson);

        assertThrows(IdempotencyConflictException.class,
            () -> idempotencyService.findCachedResponse(key, "hash-different"));
    }

    @Test
    void findCachedResponse_redisMiss_dynamoHit_backfillsRedisAndReturns() throws Exception {
        String key = "key-dynamo";
        String hash = "hashX";

        when(valueOps.get("idem:" + key)).thenReturn(null);
        when(idempotencyRepository.findByKey(key)).thenReturn(Optional.of(
            IdempotencyRecord.builder()
                .idempotencyKey(key)
                .requestHash(hash)
                .orderId("order-dynamo")
                .orderStatus("INVENTORY_RESERVED")
                .createdAt("2026-01-01T00:00:00Z")
                .build()
        ));

        CreateOrderResponse result = idempotencyService.findCachedResponse(key, hash);

        assertNotNull(result);
        assertEquals("order-dynamo", result.getOrderId());
        // Verify Redis was backfilled
        verify(valueOps).set(eq("idem:" + key), anyString(), any());
    }

    @Test
    void findCachedResponse_redisMiss_dynamoMiss_returnsNull() {
        String key = "key-new";

        when(valueOps.get("idem:" + key)).thenReturn(null);
        when(idempotencyRepository.findByKey(key)).thenReturn(Optional.empty());

        assertNull(idempotencyService.findCachedResponse(key, "anyHash"));
    }

    // ------------------------------------------------------------------
    // store
    // ------------------------------------------------------------------

    @Test
    void store_savesToDynamoAndRedis() {
        String key = "key-store";
        String hash = "hashStore";
        CreateOrderResponse response = CreateOrderResponse.builder()
            .orderId("order-stored")
            .status("INVENTORY_RESERVED")
            .createdAt("2026-01-01T00:00:00Z")
            .build();

        idempotencyService.store(key, hash, response);

        verify(idempotencyRepository).save(argThat(record ->
            record.getIdempotencyKey().equals(key) &&
            record.getRequestHash().equals(hash) &&
            record.getOrderId().equals("order-stored")
        ));
        verify(valueOps).set(eq("idem:" + key), anyString(), any());
    }

    // ------------------------------------------------------------------
    // computeRequestHash
    // ------------------------------------------------------------------

    @Test
    void computeRequestHash_deterministicForSameInput() {
        CreateOrderRequest req = buildRequest("cust-1", "item-1", 2);

        String hash1 = idempotencyService.computeRequestHash(req);
        String hash2 = idempotencyService.computeRequestHash(req);

        assertEquals(hash1, hash2);
        assertFalse(hash1.isBlank());
    }

    @Test
    void computeRequestHash_differentForDifferentBody() {
        assertNotEquals(
            idempotencyService.computeRequestHash(buildRequest("cust-1", "item-1", 1)),
            idempotencyService.computeRequestHash(buildRequest("cust-1", "item-1", 2))
        );
    }

    private CreateOrderRequest buildRequest(String customerId, String itemId, int quantity) {
        CreateOrderRequest.OrderItemDto item = new CreateOrderRequest.OrderItemDto();
        item.setItemId(itemId);
        item.setQuantity(quantity);

        CreateOrderRequest req = new CreateOrderRequest();
        req.setCustomerId(customerId);
        req.setItems(List.of(item));
        return req;
    }

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------

    private String buildRecordJson(String key, String hash, String orderId, String status, String createdAt)
        throws Exception {
        IdempotencyRecord rec = IdempotencyRecord.builder()
            .idempotencyKey(key)
            .requestHash(hash)
            .orderId(orderId)
            .orderStatus(status)
            .createdAt(createdAt)
            .build();
        return new ObjectMapper().writeValueAsString(rec);
    }
}
