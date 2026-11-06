package com.example.qrorder.tables.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.tables.dto.CreateTableRequest;
import com.example.qrorder.tables.dto.TableResponse;
import com.example.qrorder.tables.dto.UpdateTableRequest;
import com.example.qrorder.tables.model.DiningTable;
import com.example.qrorder.tables.repository.DiningTableRepository;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class TableService {

    private final DiningTableRepository tables;
    private final Clock clock;

    public TableService(DiningTableRepository tables, Clock clock) {
        this.tables = tables;
        this.clock = clock;
    }

    public TableResponse create(TenantContext tenant, CreateTableRequest request) {
        tenant.require(Permission.MANAGE_TABLES);
        String number = TableNumbers.normalize(request.number());
        if (tables.existsByHotelIdAndNumber(tenant.hotelId(), number)) {
            throw numberTaken();
        }
        try {
            return TableResponse.from(tables.save(new DiningTable(tenant.hotelId(), number,
                    request.capacity(), trimToNull(request.section()), clock.instant())));
        } catch (DuplicateKeyException e) {
            throw numberTaken(); // two creates raced; the unique index decided
        }
    }

    public List<TableResponse> list(TenantContext tenant, boolean includeInactive) {
        if (includeInactive) {
            tenant.require(Permission.MANAGE_TABLES);
        }
        List<DiningTable> found = includeInactive
                ? tables.findByHotelId(tenant.hotelId())
                : tables.findByHotelIdAndActiveTrue(tenant.hotelId());
        return found.stream()
                .sorted(Comparator.comparing(DiningTable::getNumber, TableNumbers::compare))
                .map(TableResponse::from)
                .toList();
    }

    public TableResponse get(TenantContext tenant, String tableId) {
        return TableResponse.from(find(tenant, tableId));
    }

    public TableResponse update(TenantContext tenant, String tableId, UpdateTableRequest request) {
        tenant.require(Permission.MANAGE_TABLES);
        DiningTable table = find(tenant, tableId);

        if (request.number() != null) {
            String number = TableNumbers.normalize(request.number());
            if (!number.equals(table.getNumber())) {
                if (tables.existsByHotelIdAndNumber(tenant.hotelId(), number)) {
                    throw numberTaken();
                }
                table.setNumber(number);
            }
        }
        if (request.capacity() != null) {
            table.setCapacity(request.capacity());
        }
        if (request.section() != null) {
            table.setSection(trimToNull(request.section()));
        }
        if (request.active() != null) {
            table.setActive(request.active());
        }
        table.setUpdatedAt(clock.instant());

        try {
            return TableResponse.from(tables.save(table));
        } catch (DuplicateKeyException e) {
            throw numberTaken();
        }
    }

    /** Soft delete: later sessions and orders keep pointing at this table. */
    public void deactivate(TenantContext tenant, String tableId) {
        tenant.require(Permission.MANAGE_TABLES);
        DiningTable table = find(tenant, tableId);
        table.setActive(false);
        table.setUpdatedAt(clock.instant());
        tables.save(table);
    }

    /** The only way services load a table by id: scoped to the caller's hotel. */
    private DiningTable find(TenantContext tenant, String tableId) {
        return tables.findByIdAndHotelId(tableId, tenant.hotelId())
                .orElseThrow(() -> DomainException.notFound("Table"));
    }

    private static DomainException numberTaken() {
        return DomainException.conflict("A table with this number already exists");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}