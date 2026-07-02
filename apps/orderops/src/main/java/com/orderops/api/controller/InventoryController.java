package com.orderops.api.controller;

import com.orderops.api.dto.GetInventoryResponse;
import com.orderops.api.dto.SeedInventoryRequest;
import com.orderops.api.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @PostMapping("/seed")
    @ResponseStatus(HttpStatus.CREATED)
    public GetInventoryResponse seed(@RequestBody SeedInventoryRequest request) {
        return inventoryService.seed(request);
    }

    @GetMapping("/{itemId}")
    public GetInventoryResponse getInventory(@PathVariable String itemId) {
        return inventoryService.getInventory(itemId);
    }
}
