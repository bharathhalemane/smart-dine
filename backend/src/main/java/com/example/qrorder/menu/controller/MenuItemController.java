package com.example.qrorder.menu.controller;

import com.example.qrorder.menu.dto.CreateItemRequest;
import com.example.qrorder.menu.dto.ItemResponse;
import com.example.qrorder.menu.dto.UpdateItemRequest;
import com.example.qrorder.menu.service.MenuItemService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import com.example.qrorder.media.dto.AttachImageRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/menu/items")
public class MenuItemController {

    private final MenuItemService service;

    public MenuItemController(MenuItemService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ItemResponse create(@CurrentTenant TenantContext tenant, @Valid @RequestBody CreateItemRequest request) {
        return service.create(tenant, request);
    }

    @GetMapping
    public List<ItemResponse> list(@CurrentTenant TenantContext tenant,
                                   @RequestParam(name = "categoryId", required = false) String categoryId,
                                   @RequestParam(name = "includeInactive", defaultValue = "false") boolean includeInactive) {
        return service.list(tenant, categoryId, includeInactive);
    }

    @GetMapping("/{itemId}")
    public ItemResponse get(@CurrentTenant TenantContext tenant, @PathVariable("itemId") String itemId) {
        return service.get(tenant, itemId);
    }

    @PatchMapping("/{itemId}")
    public ItemResponse update(@CurrentTenant TenantContext tenant, @PathVariable("itemId") String itemId,
                               @Valid @RequestBody UpdateItemRequest request) {
        return service.update(tenant, itemId, request);
    }

    @PutMapping("/{itemId}/image")
    public ItemResponse setImage(@CurrentTenant TenantContext tenant, @PathVariable("itemId") String itemId,
                                 @Valid @RequestBody AttachImageRequest request) {
        return service.setImage(tenant, itemId, request.assetId());
    }

    @DeleteMapping("/{itemId}/image")
    public ItemResponse clearImage(@CurrentTenant TenantContext tenant, @PathVariable("itemId") String itemId) {
        return service.clearImage(tenant, itemId);
    }

    @DeleteMapping("/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@CurrentTenant TenantContext tenant, @PathVariable("itemId") String itemId) {
        service.deactivate(tenant, itemId);
    }
}