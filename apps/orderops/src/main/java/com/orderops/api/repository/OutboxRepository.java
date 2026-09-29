package com.orderops.api.repository;

import com.orderops.shared.model.OutboxRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Outstanding fulfillment messages, keyed by order.
 *
 * <p>A record's presence means "SQS has not confirmed this message yet". Because a confirmed
 * send deletes the record, the table holds only work in flight and is normally empty — which is
 * what lets {@link #findPending} be a bounded Scan instead of needing a sparse index or a
 * status attribute to query on.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class OutboxRepository {

    private final DynamoDbClient dynamoDb;

    @Value("${tables.outbox:OrderOutbox}")
    private String tableName;

    /**
     * Returns a TransactWriteItem recording that a message is owed for this order.
     *
     * <p>Goes in the same transaction as the order itself. That is the whole point: an order can
     * no longer be committed without the obligation to enqueue it being committed alongside it,
     * so a crash between the two is impossible rather than merely unlikely.
     */
    public TransactWriteItem buildSaveTransactItem(OutboxRecord record) {
        return TransactWriteItem.builder()
            .put(Put.builder()
                .tableName(tableName)
                .item(Map.of(
                    "orderId",   AttributeValue.fromS(record.getOrderId()),
                    "payload",   AttributeValue.fromS(record.getPayload()),
                    "createdAt", AttributeValue.fromS(record.getCreatedAt())
                ))
                .build())
            .build();
    }

    /**
     * Marks the message as sent by deleting its record.
     *
     * <p>Unconditional on purpose. If the publisher and the request path both send the same
     * message, both will try to delete it, and a delete of an absent item succeeds — so the
     * duplicate is harmless. A condition here would turn that benign race into an error.
     */
    public void markSent(String orderId) {
        dynamoDb.deleteItem(DeleteItemRequest.builder()
            .tableName(tableName)
            .key(Map.of("orderId", AttributeValue.fromS(orderId)))
            .build());
    }

    /**
     * Messages still owed, oldest first, capped at {@code limit}.
     *
     * <p>A Scan is right here precisely because a healthy system leaves this table empty: there
     * is nothing to index when the expected row count is zero. If it ever holds enough rows for
     * the Scan to matter, that is itself the signal that sends are failing.
     */
    public List<OutboxRecord> findPending(int limit) {
        var resp = dynamoDb.scan(ScanRequest.builder()
            .tableName(tableName)
            .limit(limit)
            .build());

        return resp.items().stream()
            .map(item -> OutboxRecord.builder()
                .orderId(item.get("orderId").s())
                .payload(item.get("payload").s())
                .createdAt(item.get("createdAt").s())
                .build())
            .sorted(java.util.Comparator.comparing(OutboxRecord::getCreatedAt))
            .collect(Collectors.toList());
    }
}
