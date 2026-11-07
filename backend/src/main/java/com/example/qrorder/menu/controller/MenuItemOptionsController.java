package com.example.qrorder.menu.controller;

import com.example.qrorder.menu.dto.ItemResponse;
import com.example.qrorder.menu.dto.ReplaceAddOnGroupsRequest;
import com.example.qrorder.menu.dto.ReplaceVariantsRequest;
import com.example.qrorder.menu.service.MenuItemOptionsService;
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
public class MenuItemOptionsController {

    private final MenuItemOptionsService service;

    public MenuItemOptionsController(MenuItemOptionsService service) {
        this.service = service;
    }

    @PutMapping("/variants")
    public ItemResponse replaceVariants(@CurrentTenant TenantContext tenant, @PathVariable("itemId") String itemId,
                                        @Valid @RequestBody ReplaceVariantsRequest request) {
        return service.replaceVariants(tenant, itemId, request);
    }

    @PutMapping("/add-on-groups")
    public ItemResponse replaceAddOnGroups(@CurrentTenant TenantContext tenant, @PathVariable("itemId") String itemId,
                                           @Valid @RequestBody ReplaceAddOnGroupsRequest request) {
        return service.replaceAddOnGroups(tenant, itemId, request);
    }
}