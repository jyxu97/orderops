package com.orderops.api.repository;

import com.orderops.shared.model.Inventory;
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
public class InventoryRepository {

    private final DynamoDbClient dynamoDb;

    @Value("${tables.inventory:Inventory}")
    private String tableName;

    public void save(Inventory inventory) {
        dynamoDb.putItem(PutItemRequest.builder()
            .tableName(tableName)
            .item(Map.of(
                "itemId",            AttributeValue.fromS(inventory.getItemId()),
                "totalQuantity",     AttributeValue.fromN(String.valueOf(inventory.getTotalQuantity())),
                "availableQuantity", AttributeValue.fromN(String.valueOf(inventory.getAvailableQuantity())),
                "reservedQuantity",  AttributeValue.fromN(String.valueOf(inventory.getReservedQuantity())),
                "version",           AttributeValue.fromN(String.valueOf(inventory.getVersion()))
            ))
            .build());
    }

    public Optional<Inventory> findById(String itemId) {
        var resp = dynamoDb.getItem(GetItemRequest.builder()
            .tableName(tableName)
            .key(Map.of("itemId", AttributeValue.fromS(itemId)))
            .build());

        if (!resp.hasItem()) {
            return Optional.empty();
        }
        return Optional.of(mapToInventory(resp.item()));
    }

    /**
     * Conditionally reserves {@code quantity} units of {@code itemId}.
     *
     * Condition: availableQuantity >= :requested
     * Update:    availableQuantity -= qty, reservedQuantity += qty, version += 1
     *
     * @return true if the reservation succeeded; false if the condition failed (insufficient stock)
     * @throws software.amazon.awssdk.services.dynamodb.model.DynamoDbException on unexpected errors
     */
    public boolean reserveInventory(String itemId, int quantity) {
        try {
            dynamoDb.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("itemId", AttributeValue.fromS(itemId)))
                .updateExpression(
                    "SET availableQuantity = availableQuantity - :qty, " +
                    "    reservedQuantity  = reservedQuantity  + :qty, " +
                    "    #ver              = #ver              + :one")
                .conditionExpression("availableQuantity >= :qty")
                .expressionAttributeNames(Map.of("#ver", "version"))
                .expressionAttributeValues(Map.of(
                    ":qty", AttributeValue.fromN(String.valueOf(quantity)),
                    ":one", AttributeValue.fromN("1")
                ))
                .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            log.debug("Inventory reservation failed for itemId={} qty={}: insufficient stock", itemId, quantity);
            return false;
        }
    }

    /**
     * Returns a TransactWriteItem for use in a transactWriteItems call.
     * Applies the same conditional reserve logic as {@link #reserveInventory}.
     */
    public TransactWriteItem buildReserveTransactItem(String itemId, int quantity) {
        return TransactWriteItem.builder()
            .update(Update.builder()
                .tableName(tableName)
                .key(Map.of("itemId", AttributeValue.fromS(itemId)))
                .updateExpression(
                    "SET availableQuantity = availableQuantity - :qty, " +
                    "    reservedQuantity  = reservedQuantity  + :qty, " +
                    "    #ver              = #ver              + :one")
                .conditionExpression("availableQuantity >= :qty")
                .expressionAttributeNames(Map.of("#ver", "version"))
                .expressionAttributeValues(Map.of(
                    ":qty", AttributeValue.fromN(String.valueOf(quantity)),
                    ":one", AttributeValue.fromN("1")
                ))
                .build())
            .build();
    }

    private Inventory mapToInventory(Map<String, AttributeValue> item) {
        return Inventory.builder()
            .itemId(item.get("itemId").s())
            .totalQuantity(Integer.parseInt(item.get("totalQuantity").n()))
            .availableQuantity(Integer.parseInt(item.get("availableQuantity").n()))
            .reservedQuantity(Integer.parseInt(item.get("reservedQuantity").n()))
            .version(Long.parseLong(item.get("version").n()))
            .build();
    }
}
