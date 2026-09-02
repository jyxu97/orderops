package com.orderops.api.repository;

import com.orderops.shared.model.Order;
import com.orderops.shared.model.Page;
import com.orderops.shared.state.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Repository
@RequiredArgsConstructor
public class OrderRepository {

    /** GSI for customer order history: customerId (HASH) + createdAt (RANGE). */
    public static final String GSI_CUSTOMER_CREATED_AT = "GSI1_CustomerCreatedAt";

    /**
     * GSI for the operations dashboard: status (HASH) + updatedAt (RANGE).
     *
     * <p>Cardinality of the partition key is low (one partition per status), so this index is
     * only suitable for the modest read volume of an internal dashboard. A production system
     * with high ops traffic would shard the key (e.g. {@code status#<bucket>}).
     */
    public static final String GSI_STATUS_UPDATED_AT = "GSI2_StatusUpdatedAt";

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

    /** Most recent orders for a customer first, via {@link #GSI_CUSTOMER_CREATED_AT}. */
    public Page<Order> findByCustomerId(String customerId, int limit, String cursor) {
        return query(GSI_CUSTOMER_CREATED_AT, "customerId", customerId, limit, cursor);
    }

    /** Most recently updated orders in a given status first, via {@link #GSI_STATUS_UPDATED_AT}. */
    public Page<Order> findByStatus(OrderStatus status, int limit, String cursor) {
        return query(GSI_STATUS_UPDATED_AT, "status", status.name(), limit, cursor);
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

    // Used by TransactWriteItems — returns the Put object for embedding in a transaction.
    // The condition prevents duplicate order IDs (defense-in-depth; UUIDs are unique in practice).
    public Put toPutForTransaction(Order order) {
        return Put.builder()
            .tableName(tableName)
            .item(toItem(order))
            .conditionExpression("attribute_not_exists(orderId)")
            .build();
    }

    /**
     * Returns an Update that moves an order to {@code newStatus}, for embedding in a transaction.
     *
     * <p>The condition pins both the version and the current status. Pinning the version alone
     * would be enough for optimistic locking, but pinning the status as well makes the intent
     * explicit and gives a caller retrying after a lost race an unambiguous signal.
     */
    public TransactWriteItem buildStatusTransitionTransactItem(
        String orderId, OrderStatus expectedStatus, OrderStatus newStatus, long expectedVersion, String now) {

        return TransactWriteItem.builder()
            .update(Update.builder()
                .tableName(tableName)
                .key(Map.of("orderId", AttributeValue.fromS(orderId)))
                .updateExpression("SET #st = :newStatus, #ver = :newVer, updatedAt = :now")
                .conditionExpression("#ver = :expectedVer AND #st = :expectedStatus")
                .expressionAttributeNames(Map.of("#st", "status", "#ver", "version"))
                .expressionAttributeValues(Map.of(
                    ":newStatus",      AttributeValue.fromS(newStatus.name()),
                    ":expectedStatus", AttributeValue.fromS(expectedStatus.name()),
                    ":newVer",         AttributeValue.fromN(String.valueOf(expectedVersion + 1)),
                    ":expectedVer",    AttributeValue.fromN(String.valueOf(expectedVersion)),
                    ":now",            AttributeValue.fromS(now)
                ))
                .build())
            .build();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Runs a descending (newest-first) GSI query with cursor-based pagination. */
    private Page<Order> query(String indexName, String partitionKey, String partitionValue,
                              int limit, String cursor) {
        var builder = QueryRequest.builder()
            .tableName(tableName)
            .indexName(indexName)
            .keyConditionExpression("#pk = :pk")
            .expressionAttributeNames(Map.of("#pk", partitionKey))
            .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(partitionValue)))
            .scanIndexForward(false)
            .limit(limit);

        Cursors.decode(cursor).ifPresent(builder::exclusiveStartKey);

        QueryResponse resp = dynamoDb.query(builder.build());
        List<Order> orders = resp.items().stream()
            .map(this::mapToOrder)
            .collect(Collectors.toList());

        return Page.of(orders, Cursors.encode(resp.lastEvaluatedKey()));
    }

    private Map<String, AttributeValue> toItem(Order order) {
        List<AttributeValue> itemsAttr = order.getItems().stream()
            .map(i -> AttributeValue.fromM(Map.of(
                "itemId",    AttributeValue.fromS(i.getItemId()),
                "quantity",  AttributeValue.fromN(String.valueOf(i.getQuantity())),
                "unitPrice", AttributeValue.fromN(money(i.getUnitPrice()).toPlainString())
            )))
            .collect(Collectors.toList());

        return Map.of(
            "orderId",     AttributeValue.fromS(order.getOrderId()),
            "customerId",  AttributeValue.fromS(order.getCustomerId()),
            "items",       AttributeValue.fromL(itemsAttr),
            "status",      AttributeValue.fromS(order.getStatus().name()),
            "totalAmount", AttributeValue.fromN(money(order.getTotalAmount()).toPlainString()),
            "version",     AttributeValue.fromN(String.valueOf(order.getVersion())),
            "createdAt",   AttributeValue.fromS(order.getCreatedAt()),
            "updatedAt",   AttributeValue.fromS(order.getUpdatedAt())
        );
    }

    private Order mapToOrder(Map<String, AttributeValue> item) {
        List<Order.OrderItem> items = item.get("items").l().stream()
            .map(av -> Order.OrderItem.builder()
                .itemId(av.m().get("itemId").s())
                .quantity(Integer.parseInt(av.m().get("quantity").n()))
                .unitPrice(readMoney(av.m().get("unitPrice")))
                .build())
            .collect(Collectors.toList());

        return Order.builder()
            .orderId(item.get("orderId").s())
            .customerId(item.get("customerId").s())
            .items(items)
            .status(OrderStatus.valueOf(item.get("status").s()))
            .totalAmount(readMoney(item.get("totalAmount")))
            .version(Long.parseLong(item.get("version").n()))
            .createdAt(item.get("createdAt").s())
            .updatedAt(item.get("updatedAt").s())
            .build();
    }

    private static BigDecimal money(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /** Orders written before pricing existed have no amount attributes. */
    private static BigDecimal readMoney(AttributeValue attr) {
        return attr != null ? new BigDecimal(attr.n()) : BigDecimal.ZERO;
    }
}
