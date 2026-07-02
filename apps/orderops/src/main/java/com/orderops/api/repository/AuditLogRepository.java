package com.orderops.api.repository;

import com.orderops.shared.model.OrderAuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.HashMap;
import java.util.Map;

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
}
