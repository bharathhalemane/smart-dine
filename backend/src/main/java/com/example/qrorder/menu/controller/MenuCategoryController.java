package com.example.qrorder.menu.controller;

import com.example.qrorder.menu.dto.CategoryResponse;
import com.example.qrorder.menu.dto.CreateCategoryRequest;
import com.example.qrorder.menu.dto.ReorderCategoriesRequest;
import com.example.qrorder.menu.dto.UpdateCategoryRequest;
import com.example.qrorder.menu.service.MenuCategoryService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/menu/categories")
public class MenuCategoryController {

    private final MenuCategoryService service;

    public MenuCategoryController(MenuCategoryService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse create(@CurrentTenant TenantContext tenant,
                                   @Valid @RequestBody CreateCategoryRequest request) {
        return service.create(tenant, request);
    }

    @GetMapping
    public List<CategoryResponse> list(@CurrentTenant TenantContext tenant,
                                       @RequestParam(name = "includeInactive", defaultValue = "false") boolean includeInactive) {
        return service.list(tenant, includeInactive);
    }

    @PutMapping("/order")
    public List<CategoryResponse> reorder(@CurrentTenant TenantContext tenant,
                                          @Valid @RequestBody ReorderCategoriesRequest request) {
        return service.reorder(tenant, request);
    }

    @GetMapping("/{categoryId}")
    public CategoryResponse get(@CurrentTenant TenantContext tenant, @PathVariable("categoryId") String categoryId) {
        return service.get(tenant, categoryId);
    }

    @PatchMapping("/{categoryId}")
    public CategoryResponse update(@CurrentTenant TenantContext tenant, @PathVariable("categoryId") String categoryId,
                                   @Valid @RequestBody UpdateCategoryRequest request) {
        return service.update(tenant, categoryId, request);
    }

    @DeleteMapping("/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@CurrentTenant TenantContext tenant, @PathVariable("categoryId") String categoryId) {
        service.deactivate(tenant, categoryId);
    }
}