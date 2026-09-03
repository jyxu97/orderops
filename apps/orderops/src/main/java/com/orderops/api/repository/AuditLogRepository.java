package com.orderops.api.repository;

import com.orderops.shared.model.OrderAuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Repository
@RequiredArgsConstructor
public class AuditLogRepository {

    private final DynamoDbClient dynamoDb;

    @Value("${tables.audit:OrderAuditLogs}")
    private String tableName;

    public void save(OrderAuditLog log) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("orderId",    AttributeValue.fromS(log.getOrderId()));
        item.put("timestamp",  AttributeValue.fromS(log.getTimestamp()));
        item.put("fromStatus", AttributeValue.fromS(log.getFromStatus()));
        item.put("toStatus",   AttributeValue.fromS(log.getToStatus()));
        if (log.getReason() != null) {
            item.put("reason", AttributeValue.fromS(log.getReason()));
        }

        dynamoDb.putItem(PutItemRequest.builder()
            .tableName(tableName)
            .item(item)
            .build());
    }

    /**
     * The full transition history for one order, oldest first.
     *
     * <p>The table's sort key is the ISO-8601 timestamp, so the index order is already
     * chronological — no client-side sorting needed.
     */
    public List<OrderAuditLog> findByOrderId(String orderId) {
        return query(orderId, true, 100);
    }

    /**
     * The most recent transition for one order, or empty if it has no audit history.
     *
     * <p>Reads a single item in descending sort order rather than the whole history.
     */
    public Optional<OrderAuditLog> findLatestByOrderId(String orderId) {
        return query(orderId, false, 1).stream().findFirst();
    }

    /**
     * The {@code limit} most recent transitions for one order, newest first.
     *
     * <p>Bounded rather than the full history because the operations views ask for many orders
     * at once and only need the tail of each one.
     */
    public List<OrderAuditLog> findRecentByOrderId(String orderId, int limit) {
        return query(orderId, false, limit);
    }

    private List<OrderAuditLog> query(String orderId, boolean ascending, int limit) {
        var resp = dynamoDb.query(QueryRequest.builder()
            .tableName(tableName)
            .keyConditionExpression("orderId = :orderId")
            .expressionAttributeValues(Map.of(":orderId", AttributeValue.fromS(orderId)))
            .scanIndexForward(ascending)
            .limit(limit)
            .build());

        return resp.items().stream()
            .map(AuditLogRepository::mapToAuditLog)
            .collect(Collectors.toList());
    }

    private static OrderAuditLog mapToAuditLog(Map<String, AttributeValue> item) {
        AttributeValue reason = item.get("reason");
        return OrderAuditLog.builder()
            .orderId(item.get("orderId").s())
            .timestamp(item.get("timestamp").s())
            .fromStatus(item.get("fromStatus").s())
            .toStatus(item.get("toStatus").s())
            .reason(reason != null ? reason.s() : null)
            .build();
    }
}
