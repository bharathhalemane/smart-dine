package com.example.qrorder.menu.controller;

import com.example.qrorder.menu.dto.ItemResponse;
import com.example.qrorder.menu.dto.SetAvailabilityRequest;
import com.example.qrorder.menu.service.MenuAvailabilityService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/menu/items/{itemId}")
public class MenuAvailabilityController {

    private final MenuAvailabilityService service;

    public MenuAvailabilityController(MenuAvailabilityService service) {
        this.service = service;
    }

    @PutMapping("/availability")
    public ItemResponse setAvailability(@CurrentTenant TenantContext tenant, @PathVariable("itemId") String itemId,
                                        @Valid @RequestBody SetAvailabilityRequest request) {
        return service.setAvailable(tenant, itemId, request.available());
    }
}