package com.orderops.api.repository;

import com.orderops.shared.model.Inventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

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
}
