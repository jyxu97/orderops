package com.orderops.api.repository;

import com.orderops.shared.model.Order;
import com.orderops.shared.state.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Repository
@RequiredArgsConstructor
public class OrderRepository {

    private final DynamoDbClient dynamoDb;

    @Value("${tables.orders:Orders}")
    private String tableName;

    public void save(Order order) {
        dynamoDb.putItem(PutItemRequest.builder()
            .tableName(tableName)
            .item(toItem(order))
            .build());
    }

    public Optional<Order> findById(String orderId) {
        var resp = dynamoDb.getItem(GetItemRequest.builder()
            .tableName(tableName)
            .key(Map.of("orderId", AttributeValue.fromS(orderId)))
            .build());

        if (!resp.hasItem()) {
            return Optional.empty();
        }
        return Optional.of(mapToOrder(resp.item()));
    }

    /**
     * Conditionally updates order status and increments version.
     * Uses optimistic locking: fails if {@code expectedVersion} doesn't match.
     */
    public void updateStatus(String orderId, OrderStatus newStatus, long expectedVersion) {
        try {
            dynamoDb.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("orderId", AttributeValue.fromS(orderId)))
                .updateExpression("SET #st = :status, #ver = :newVer, updatedAt = :now")
                .conditionExpression("#ver = :expectedVer")
                .expressionAttributeNames(Map.of("#st", "status", "#ver", "version"))
                .expressionAttributeValues(Map.of(
                    ":status",      AttributeValue.fromS(newStatus.name()),
                    ":newVer",      AttributeValue.fromN(String.valueOf(expectedVersion + 1)),
                    ":expectedVer", AttributeValue.fromN(String.valueOf(expectedVersion)),
                    ":now",         AttributeValue.fromS(Instant.now().toString())
                ))
                .build());
        } catch (ConditionalCheckFailedException e) {
            throw new RuntimeException(
                "Version conflict updating order " + orderId + " to " + newStatus, e);
        }
    }

    // Used by TransactWriteItems — returns the Put object for embedding in a transaction
    public Put toPutForTransaction(Order order) {
        return Put.builder()
            .tableName(tableName)
            .item(toItem(order))
            .build();
    }

    private Map<String, AttributeValue> toItem(Order order) {
        List<AttributeValue> itemsAttr = order.getItems().stream()
            .map(i -> AttributeValue.fromM(Map.of(
                "itemId",   AttributeValue.fromS(i.getItemId()),
                "quantity", AttributeValue.fromN(String.valueOf(i.getQuantity()))
            )))
            .collect(Collectors.toList());

        return Map.of(
            "orderId",    AttributeValue.fromS(order.getOrderId()),
            "customerId", AttributeValue.fromS(order.getCustomerId()),
            "items",      AttributeValue.fromL(itemsAttr),
            "status",     AttributeValue.fromS(order.getStatus().name()),
            "version",    AttributeValue.fromN(String.valueOf(order.getVersion())),
            "createdAt",  AttributeValue.fromS(order.getCreatedAt()),
            "updatedAt",  AttributeValue.fromS(order.getUpdatedAt())
        );
    }

    private Order mapToOrder(Map<String, AttributeValue> item) {
        List<Order.OrderItem> items = item.get("items").l().stream()
            .map(av -> Order.OrderItem.builder()
                .itemId(av.m().get("itemId").s())
                .quantity(Integer.parseInt(av.m().get("quantity").n()))
                .build())
            .collect(Collectors.toList());

        return Order.builder()
            .orderId(item.get("orderId").s())
            .customerId(item.get("customerId").s())
            .items(items)
            .status(OrderStatus.valueOf(item.get("status").s()))
            .version(Long.parseLong(item.get("version").n()))
            .createdAt(item.get("createdAt").s())
            .updatedAt(item.get("updatedAt").s())
            .build();
    }
}
