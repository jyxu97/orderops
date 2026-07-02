package com.orderops.api.repository;

import com.orderops.shared.model.IdempotencyRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class IdempotencyRepository {

    private final DynamoDbClient dynamoDb;

    @Value("${tables.idempotency:IdempotencyRecords}")
    private String tableName;

    public void save(IdempotencyRecord record) {
        dynamoDb.putItem(PutItemRequest.builder()
            .tableName(tableName)
            .item(Map.of(
                "idempotencyKey", AttributeValue.fromS(record.getIdempotencyKey()),
                "requestHash",    AttributeValue.fromS(record.getRequestHash()),
                "orderId",        AttributeValue.fromS(record.getOrderId()),
                "orderStatus",    AttributeValue.fromS(record.getOrderStatus()),
                "createdAt",      AttributeValue.fromS(record.getCreatedAt())
            ))
            .build());
    }

    public Optional<IdempotencyRecord> findByKey(String idempotencyKey) {
        var resp = dynamoDb.getItem(GetItemRequest.builder()
            .tableName(tableName)
            .key(Map.of("idempotencyKey", AttributeValue.fromS(idempotencyKey)))
            .build());

        if (!resp.hasItem()) {
            return Optional.empty();
        }
        var item = resp.item();
        return Optional.of(IdempotencyRecord.builder()
            .idempotencyKey(item.get("idempotencyKey").s())
            .requestHash(item.get("requestHash").s())
            .orderId(item.get("orderId").s())
            .orderStatus(item.get("orderStatus").s())
            .createdAt(item.get("createdAt").s())
            .build());
    }
}
