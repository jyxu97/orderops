package com.orderops.api.repository;

import com.orderops.shared.model.Inventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import static org.junit.jupiter.api.Assertions.*;

class InventoryRepositoryTest extends DynamoDbTestBase {

    private InventoryRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InventoryRepository(dynamoDb);
        ReflectionTestUtils.setField(repository, "tableName", "Inventory");
    }

    @Test
    void saveAndFindById_roundTrip() {
        Inventory inv = Inventory.builder()
            .itemId("item-rt-" + System.nanoTime())
            .totalQuantity(100)
            .availableQuantity(100)
            .reservedQuantity(0)
            .version(0L)
            .build();
        repository.save(inv);

        Optional<Inventory> found = repository.findById(inv.getItemId());
        assertTrue(found.isPresent());
        assertEquals(100, found.get().getAvailableQuantity());
    }

    @Test
    void findById_notFound_returnsEmpty() {
        Optional<Inventory> result = repository.findById("nonexistent-" + System.nanoTime());
        assertTrue(result.isEmpty());
    }

    @Test
    void reserveInventory_succeeds_whenSufficientStock() {
        String itemId = "item-rsv-" + System.nanoTime();
        repository.save(Inventory.builder()
            .itemId(itemId).totalQuantity(10).availableQuantity(10).reservedQuantity(0).version(0L).build());

        boolean result = repository.reserveInventory(itemId, 5);
        assertTrue(result);

        Inventory updated = repository.findById(itemId).orElseThrow();
        assertEquals(5, updated.getAvailableQuantity());
        assertEquals(5, updated.getReservedQuantity());
    }

    @Test
    void reserveInventory_fails_whenInsufficientStock() {
        String itemId = "item-insuf-" + System.nanoTime();
        repository.save(Inventory.builder()
            .itemId(itemId).totalQuantity(3).availableQuantity(3).reservedQuantity(0).version(0L).build());

        boolean result = repository.reserveInventory(itemId, 5);
        assertFalse(result);

        Inventory unchanged = repository.findById(itemId).orElseThrow();
        assertEquals(3, unchanged.getAvailableQuantity());
    }

    @Test
    void reserveInventory_concurrent_noOversell() throws InterruptedException {
        int totalStock = 50;
        int concurrentRequests = 100;
        int reservePerRequest = 1;
        String itemId = "item-concurrent-" + System.nanoTime();

        repository.save(Inventory.builder()
            .itemId(itemId)
            .totalQuantity(totalStock)
            .availableQuantity(totalStock)
            .reservedQuantity(0)
            .version(0L)
            .build());

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < concurrentRequests; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    boolean reserved = repository.reserveInventory(itemId, reservePerRequest);
                    if (reserved) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        startLatch.countDown();
        for (Future<?> f : futures) {
            try { f.get(30, TimeUnit.SECONDS); } catch (ExecutionException | TimeoutException ignored) {}
        }
        executor.shutdown();

        Inventory final_ = repository.findById(itemId).orElseThrow();
        assertEquals(totalStock, successCount.get(),
            "Exactly " + totalStock + " reservations should succeed");
        assertEquals(concurrentRequests - totalStock, failureCount.get(),
            "Remaining requests should fail");
        assertEquals(0, final_.getAvailableQuantity(),
            "Available quantity must be 0 — no oversell");
        assertEquals(totalStock, final_.getReservedQuantity(),
            "Reserved quantity must equal total stock");
    }

    @Test
    void saveAndFindById_roundTripsUnitPrice() {
        String itemId = "item-price-" + System.nanoTime();
        repository.save(Inventory.builder()
            .itemId(itemId).itemName("Widget").unitPrice(new BigDecimal("19.99"))
            .totalQuantity(10).availableQuantity(10).reservedQuantity(0).version(0L).build());

        Inventory found = repository.findById(itemId).orElseThrow();
        assertEquals(0, new BigDecimal("19.99").compareTo(found.getUnitPrice()));
        assertEquals("Widget", found.getItemName());
    }

    @Test
    void findById_itemSavedWithoutPrice_defaultsToZero() {
        String itemId = "item-nopr-" + System.nanoTime();
        repository.save(Inventory.builder()
            .itemId(itemId).totalQuantity(1).availableQuantity(1).reservedQuantity(0).version(0L).build());

        assertEquals(0, BigDecimal.ZERO.compareTo(repository.findById(itemId).orElseThrow().getUnitPrice()));
    }

    @Test
    void findAllById_returnsRequestedItemsAndOmitsUnknownOnes() {
        String first  = "item-batch-a-" + System.nanoTime();
        String second = "item-batch-b-" + System.nanoTime();
        repository.save(Inventory.builder()
            .itemId(first).unitPrice(new BigDecimal("5.00"))
            .totalQuantity(4).availableQuantity(4).reservedQuantity(0).version(0L).build());
        repository.save(Inventory.builder()
            .itemId(second).unitPrice(new BigDecimal("7.25"))
            .totalQuantity(4).availableQuantity(4).reservedQuantity(0).version(0L).build());

        Map<String, Inventory> found = repository.findAllById(Set.of(first, second, "missing-sku"));

        assertEquals(Set.of(first, second), found.keySet());
        assertEquals(0, new BigDecimal("7.25").compareTo(found.get(second).getUnitPrice()));
    }

    @Test
    void findAllById_emptyRequest_returnsEmptyMapWithoutCallingDynamo() {
        assertTrue(repository.findAllById(Set.of()).isEmpty());
    }

    @Test
    void releaseTransactItem_isTheExactInverseOfReserve() {
        String itemId = "item-rel-" + System.nanoTime();
        repository.save(Inventory.builder()
            .itemId(itemId).totalQuantity(10).availableQuantity(10).reservedQuantity(0).version(0L).build());

        assertTrue(repository.reserveInventory(itemId, 4));
        applyTransaction(repository.buildReleaseTransactItem(itemId, 4));

        Inventory restored = repository.findById(itemId).orElseThrow();
        assertEquals(10, restored.getAvailableQuantity());
        assertEquals(0, restored.getReservedQuantity());
    }

    @Test
    void releaseTransactItem_cannotDriveReservedQuantityNegative() {
        String itemId = "item-relneg-" + System.nanoTime();
        repository.save(Inventory.builder()
            .itemId(itemId).totalQuantity(10).availableQuantity(10).reservedQuantity(0).version(0L).build());

        assertTrue(repository.reserveInventory(itemId, 2));
        applyTransaction(repository.buildReleaseTransactItem(itemId, 2));

        // A second release of the same reservation must be rejected, not silently applied —
        // otherwise availableQuantity would exceed totalQuantity and stock would be conjured.
        assertThrows(TransactionCanceledException.class,
            () -> applyTransaction(repository.buildReleaseTransactItem(itemId, 2)));

        Inventory unchanged = repository.findById(itemId).orElseThrow();
        assertEquals(10, unchanged.getAvailableQuantity());
        assertEquals(0, unchanged.getReservedQuantity());
    }

    private void applyTransaction(TransactWriteItem item) {
        dynamoDb.transactWriteItems(TransactWriteItemsRequest.builder()
            .transactItems(item)
            .build());
    }
}
