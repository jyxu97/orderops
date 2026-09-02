package com.orderops.shared.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * One page of DynamoDB query results.
 *
 * <p>{@code nextCursor} is an opaque, base64-encoded encoding of the DynamoDB
 * {@code LastEvaluatedKey}. It is {@code null} when the result set is exhausted.
 */
@Value
@Builder
public class Page<T> {
    List<T> items;
    String nextCursor;

    public static <T> Page<T> of(List<T> items, String nextCursor) {
        return Page.<T>builder().items(items).nextCursor(nextCursor).build();
    }
}
