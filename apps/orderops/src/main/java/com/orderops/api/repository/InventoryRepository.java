package com.orderops.api.repository;

import com.orderops.shared.model.Inventory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Repository
@RequiredArgsConstructor
public class InventoryRepository {

    private final DynamoDbClient dynamoDb;

    @Value("${tables.inventory:Inventory}")
    private String tableName;

    public void save(Inventory inventory) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("itemId",            AttributeValue.fromS(inventory.getItemId()));
        item.put("totalQuantity",     AttributeValue.fromN(String.valueOf(inventory.getTotalQuantity())));
        item.put("availableQuantity", AttributeValue.fromN(String.valueOf(inventory.getAvailableQuantity())));
        item.put("reservedQuantity",  AttributeValue.fromN(String.valueOf(inventory.getReservedQuantity())));
        item.put("version",           AttributeValue.fromN(String.valueOf(inventory.getVersion())));
        if (inventory.getItemName() != null) {
            item.put("itemName", AttributeValue.fromS(inventory.getItemName()));
        }
        dynamoDb.putItem(PutItemRequest.builder()
            .tableName(tableName)
            .item(item)
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
     * Returns the full inventory catalog, sorted by itemId.
     *
     * <p>A Scan is acceptable here: the catalog is small and bounded (this is a demo storefront,
     * not a product search service). {@code limit} caps the number of items read so a growing
     * table can never turn this into an unbounded scan.
     */
    public List<Inventory> findAll(int limit) {
        var resp = dynamoDb.scan(ScanRequest.builder()
            .tableName(tableName)
            .limit(limit)
            .build());

        return resp.items().stream()
            .map(this::mapToInventory)
            .sorted(Comparator.comparing(Inventory::getItemId))
            .collect(Collectors.toList());
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
        AttributeValue nameAttr = item.get("itemName");
        return Inventory.builder()
            .itemId(item.get("itemId").s())
            .itemName(nameAttr != null ? nameAttr.s() : null)
            .totalQuantity(Integer.parseInt(item.get("totalQuantity").n()))
            .availableQuantity(Integer.parseInt(item.get("availableQuantity").n()))
            .reservedQuantity(Integer.parseInt(item.get("reservedQuantity").n()))
            .version(Long.parseLong(item.get("version").n()))
            .build();
    }
}
