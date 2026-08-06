package com.orderops.api.service;

import com.orderops.api.dto.GetInventoryResponse;
import com.orderops.api.dto.SeedInventoryRequest;
import com.orderops.api.repository.InventoryRepository;
import com.orderops.shared.model.Inventory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    public GetInventoryResponse seed(SeedInventoryRequest request) {
        Inventory inventory = Inventory.builder()
            .itemId(request.getItemId())
            .itemName(request.getItemName())
            .totalQuantity(request.getQuantity())
            .availableQuantity(request.getQuantity())
            .reservedQuantity(0)
            .version(0L)
            .build();
        inventoryRepository.save(inventory);
        log.info("Seeded inventory itemId={} qty={}", request.getItemId(), request.getQuantity());
        return toResponse(inventory);
    }

    public GetInventoryResponse getInventory(String itemId) {
        return inventoryRepository.findById(itemId)
            .map(this::toResponse)
            .orElseThrow(() -> new RuntimeException("Inventory not found: " + itemId));
    }

    private GetInventoryResponse toResponse(Inventory inv) {
        return GetInventoryResponse.builder()
            .itemId(inv.getItemId())
            .itemName(inv.getItemName())
            .totalQuantity(inv.getTotalQuantity())
            .availableQuantity(inv.getAvailableQuantity())
            .reservedQuantity(inv.getReservedQuantity())
            .version(inv.getVersion())
            .build();
    }
}
