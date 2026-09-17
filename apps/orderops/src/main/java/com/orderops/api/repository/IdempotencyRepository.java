package com.orderops.api.repository;

import com.orderops.shared.model.IdempotencyRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class IdempotencyRepository {

    private final DynamoDbClient dynamoDb;

    @Value("${tables.idempotency:IdempotencyRecords}")
    private String tableName;

    /** Returns a TransactWriteItem for use in a transactWriteItems call. */
    public TransactWriteItem buildSaveTransactItem(IdempotencyRecord record) {
        return TransactWriteItem.builder()
            .put(Put.builder()
                .tableName(tableName)
                .item(Map.of(
                    "idempotencyKey", AttributeValue.fromS(record.getIdempotencyKey()),
                    "requestHash",    AttributeValue.fromS(record.getRequestHash()),
                    "orderId",        AttributeValue.fromS(record.getOrderId()),
                    "orderStatus",    AttributeValue.fromS(record.getOrderStatus()),
                    "totalAmount",    AttributeValue.fromN(totalAmountOf(record).toPlainString()),
                    "createdAt",      AttributeValue.fromS(record.getCreatedAt())
                ))
                .conditionExpression("attribute_not_exists(idempotencyKey)")
                .build())
            .build();
    }

    /**
     * Reads an idempotency record with a strongly consistent read.
     *
     * <p>Consistency is required, not an optimisation choice. This is called after a
     * TransactWriteItems has already reported that the key exists, so the record is known to be
     * committed — but DynamoDB's default eventually consistent read can still miss a write made
     * microseconds earlier, which is exactly the timing of a client retrying a request that
     * timed out. An eventually consistent read here would intermittently fail to find a record
     * the database just told us about.
     *
     * <p>The doubled read cost is irrelevant: this path only runs for duplicate requests.
     */
    public Optional<IdempotencyRecord> findByKey(String idempotencyKey) {
        var resp = dynamoDb.getItem(GetItemRequest.builder()
            .tableName(tableName)
            .key(Map.of("idempotencyKey", AttributeValue.fromS(idempotencyKey)))
            .consistentRead(true)
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
            // Records written before pricing existed have no totalAmount attribute.
            .totalAmount(item.containsKey("totalAmount")
                ? new BigDecimal(item.get("totalAmount").n())
                : BigDecimal.ZERO)
            .createdAt(item.get("createdAt").s())
            .build());
    }

    private static BigDecimal totalAmountOf(IdempotencyRecord record) {
        return record.getTotalAmount() != null ? record.getTotalAmount() : BigDecimal.ZERO;
    }
}
