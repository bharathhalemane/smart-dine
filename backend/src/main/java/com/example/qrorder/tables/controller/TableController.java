package com.example.qrorder.tables.controller;

import com.example.qrorder.tables.dto.CreateTableRequest;
import com.example.qrorder.tables.dto.TableResponse;
import com.example.qrorder.tables.dto.UpdateTableRequest;
import com.example.qrorder.tables.service.TableService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/tables")
public class TableController {

    private final TableService service;

    public TableController(TableService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TableResponse create(@CurrentTenant TenantContext tenant, @Valid @RequestBody CreateTableRequest request) {
        return service.create(tenant, request);
    }

    @GetMapping
    public List<TableResponse> list(@CurrentTenant TenantContext tenant,
                                    @RequestParam(name = "includeInactive", defaultValue = "false") boolean includeInactive) {
        return service.list(tenant, includeInactive);
    }

    @GetMapping("/{tableId}")
    public TableResponse get(@CurrentTenant TenantContext tenant, @PathVariable("tableId") String tableId) {
        return service.get(tenant, tableId);
    }

    @PatchMapping("/{tableId}")
    public TableResponse update(@CurrentTenant TenantContext tenant, @PathVariable("tableId") String tableId,
                                @Valid @RequestBody UpdateTableRequest request) {
        return service.update(tenant, tableId, request);
    }

    @DeleteMapping("/{tableId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@CurrentTenant TenantContext tenant, @PathVariable("tableId") String tableId) {
        service.deactivate(tenant, tableId);
    }
}