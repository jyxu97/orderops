package com.orderops.api.controller;

import com.orderops.api.dto.GetInventoryResponse;
import com.orderops.api.dto.SeedInventoryRequest;
import com.orderops.api.service.InventoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @PostMapping("/seed")
    @ResponseStatus(HttpStatus.CREATED)
    public GetInventoryResponse seed(@Valid @RequestBody SeedInventoryRequest request) {
        return inventoryService.seed(request);
    }

    @GetMapping
    public List<GetInventoryResponse> listInventory(
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit
    ) {
        return inventoryService.listInventory(limit);
    }

    @GetMapping("/{itemId}")
    public GetInventoryResponse getInventory(@PathVariable String itemId) {
        return inventoryService.getInventory(itemId);
    }
}
