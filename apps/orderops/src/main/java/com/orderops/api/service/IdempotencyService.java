package com.orderops.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderops.api.dto.CreateOrderRequest;
import com.orderops.api.dto.CreateOrderResponse;
import com.orderops.api.exception.IdempotencyConflictException;
import com.orderops.api.repository.IdempotencyRepository;
import com.orderops.shared.model.IdempotencyRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String REDIS_PREFIX = "idem:";
    private static final Duration REDIS_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    /**
     * Looks up a cached response for the given idempotency key.
     *
     * <ol>
     *   <li>Fast path: Redis ({@code idem:{key}})</li>
     *   <li>Slow path: DynamoDB IdempotencyRecords (backfills Redis on hit)</li>
     * </ol>
     *
     * @return the cached {@link CreateOrderResponse} if the key was seen before with the same
     *         request hash; {@code null} if this is a brand-new request
     * @throws IdempotencyConflictException if the key exists but was used with a different body
     */
    public CreateOrderResponse findCachedResponse(String idempotencyKey, String requestHash) {
        // 1. Redis fast path
        String cachedJson = safeRedisGet(idempotencyKey);
        if (cachedJson != null) {
            log.debug("Idempotency cache hit (Redis) key={}", idempotencyKey);
            return validateAndBuild(cachedJson, requestHash, idempotencyKey);
        }

        // 2. DynamoDB slow path
        Optional<IdempotencyRecord> record = idempotencyRepository.findByKey(idempotencyKey);
        if (record.isPresent()) {
            log.debug("Idempotency cache hit (DynamoDB) key={}", idempotencyKey);
            String json = toJson(record.get());
            safeRedisSet(idempotencyKey, json); // backfill
            return validateAndBuild(json, requestHash, idempotencyKey);
        }

        return null; // new request
    }

    /**
     * Persists the idempotency record to DynamoDB and caches it in Redis.
     * Called after a successful order creation.
     */
    public void store(String idempotencyKey, String requestHash, CreateOrderResponse response) {
        IdempotencyRecord record = IdempotencyRecord.builder()
            .idempotencyKey(idempotencyKey)
            .requestHash(requestHash)
            .orderId(response.getOrderId())
            .orderStatus(response.getStatus())
            .createdAt(response.getCreatedAt())
            .build();

        idempotencyRepository.save(record);
        safeRedisSet(idempotencyKey, toJson(record));
        log.info("Stored idempotency record key={} orderId={}", idempotencyKey, response.getOrderId());
    }

    /** Computes SHA-256 of the canonical JSON representation of the request. */
    public String computeRequestHash(CreateOrderRequest request) {
        try {
            String json = objectMapper.writeValueAsString(request);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute request hash", e);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private CreateOrderResponse validateAndBuild(String json, String requestHash, String idempotencyKey) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String storedHash = node.get("requestHash").asText();
            if (!storedHash.equals(requestHash)) {
                throw new IdempotencyConflictException(idempotencyKey);
            }
            return CreateOrderResponse.builder()
                .orderId(node.get("orderId").asText())
                .status(node.get("orderStatus").asText())
                .createdAt(node.get("createdAt").asText())
                .build();
        } catch (IdempotencyConflictException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize idempotency cache entry", e);
        }
    }

    private String safeRedisGet(String idempotencyKey) {
        try {
            return redis.opsForValue().get(REDIS_PREFIX + idempotencyKey);
        } catch (Exception e) {
            log.warn("Redis get failed for key={}, falling back to DynamoDB", idempotencyKey);
            return null;
        }
    }

    private void safeRedisSet(String idempotencyKey, String json) {
        try {
            redis.opsForValue().set(REDIS_PREFIX + idempotencyKey, json, REDIS_TTL);
        } catch (Exception e) {
            log.warn("Redis set failed for key={}, continuing without cache", idempotencyKey);
        }
    }

    private String toJson(IdempotencyRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize idempotency record", e);
        }
    }
}
