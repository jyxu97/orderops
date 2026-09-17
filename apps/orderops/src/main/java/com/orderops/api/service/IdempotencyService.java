package com.orderops.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderops.api.dto.CreateOrderRequest;
import com.orderops.api.dto.CreateOrderResponse;
import com.orderops.api.repository.IdempotencyRepository;
import com.orderops.api.exception.IdempotencyConflictException;
import com.orderops.shared.model.IdempotencyRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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
     * Resolves a request whose idempotency key already exists.
     *
     * <p>Called only after a TransactWriteItems has failed its
     * {@code attribute_not_exists(idempotencyKey)} condition — so the key is known to exist and
     * this is not a speculative lookup. There is deliberately no pre-check before the
     * transaction: the conditional write is strongly consistent and already decides the
     * question, so reading first would only add two round trips to every *new* order to save
     * one on the rare duplicate.
     *
     * <ol>
     *   <li>Redis ({@code idem:{key}}) — saves a DynamoDB read when duplicates are frequent</li>
     *   <li>DynamoDB, strongly consistent — the authority, and correct even when Redis is cold,
     *       stale or down</li>
     * </ol>
     *
     * @return the response the original request returned
     * @throws IdempotencyConflictException if the key was reused with a different body
     * @throws IllegalStateException if neither store has the record, which would mean the
     *         conditional check and the data disagree
     */
    public CreateOrderResponse resolveDuplicate(String idempotencyKey, String requestHash) {
        String cachedJson = safeRedisGet(idempotencyKey);
        if (cachedJson != null) {
            log.debug("Duplicate resolved from Redis key={}", idempotencyKey);
            return validateAndBuild(cachedJson, requestHash, idempotencyKey);
        }

        Optional<IdempotencyRecord> record = idempotencyRepository.findByKey(idempotencyKey);
        if (record.isPresent()) {
            log.debug("Duplicate resolved from DynamoDB key={}", idempotencyKey);
            String json = toJson(record.get());
            safeRedisSet(idempotencyKey, json); // warm the cache for any further retries
            return validateAndBuild(json, requestHash, idempotencyKey);
        }

        throw new IllegalStateException(
            "Idempotency key " + idempotencyKey + " failed its attribute_not_exists condition "
                + "but no record was found on a consistent read");
    }

    /**
     * Writes the Redis cache entry after the DynamoDB idempotency record has
     * been persisted inside a TransactWriteItems call.
     */
    public void cacheResponseInRedis(String idempotencyKey, String requestHash, CreateOrderResponse response) {
        IdempotencyRecord record = IdempotencyRecord.builder()
            .idempotencyKey(idempotencyKey)
            .requestHash(requestHash)
            .orderId(response.getOrderId())
            .orderStatus(response.getStatus())
            .totalAmount(response.getTotalAmount())
            .createdAt(response.getCreatedAt())
            .build();
        safeRedisSet(idempotencyKey, toJson(record));
        log.info("Cached idempotency record in Redis key={} orderId={}", idempotencyKey, response.getOrderId());
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
            JsonNode total = node.get("totalAmount");
            return CreateOrderResponse.builder()
                .orderId(node.get("orderId").asText())
                .status(node.get("orderStatus").asText())
                .totalAmount(total != null && !total.isNull() ? total.decimalValue() : BigDecimal.ZERO)
                .createdAt(node.get("createdAt").asText())
                .replayed(true)
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
