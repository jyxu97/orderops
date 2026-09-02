package com.orderops.api.repository;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Encodes and decodes DynamoDB pagination keys as opaque base64 cursors.
 *
 * <p>All key attributes used by the Orders GSIs are strings, so the wire format is a flat
 * {@code name=value} list joined by {@code ;} and base64url-encoded. Callers must treat the
 * result as opaque — the encoding is an implementation detail.
 */
final class Cursors {

    private Cursors() {}

    static String encode(Map<String, AttributeValue> lastEvaluatedKey) {
        if (lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        lastEvaluatedKey.forEach((name, value) -> {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(name).append('=').append(value.s());
        });
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    static Optional<Map<String, AttributeValue>> decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return Optional.empty();
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            Map<String, AttributeValue> key = new LinkedHashMap<>();
            for (String pair : raw.split(";")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    throw new IllegalArgumentException("malformed cursor segment: " + pair);
                }
                key.put(pair.substring(0, eq), AttributeValue.fromS(pair.substring(eq + 1)));
            }
            return Optional.of(key);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid pagination cursor", e);
        }
    }
}
