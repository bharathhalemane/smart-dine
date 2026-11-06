package com.example.qrorder.tables.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.tables.dto.IssuedQrResponse;
import com.example.qrorder.tables.model.DiningTable;
import com.example.qrorder.tables.repository.DiningTableRepository;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import com.example.qrorder.tenancy.service.HotelService;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QrSheetService {

    static final int MAX_TABLES = 100;

    private final DiningTableRepository tables;
    private final QrCredentialService qrCredentials;
    private final HotelService hotelService;

    public QrSheetService(DiningTableRepository tables, QrCredentialService qrCredentials, HotelService hotelService) {
        this.tables = tables;
        this.qrCredentials = qrCredentials;
        this.hotelService = hotelService;
    }

    /**
     * Issues (rotates) a QR credential for each selected table and renders them on one printable page.
     * All or nothing: one bad table id fails the request and nothing is rotated.
     */
    @Transactional
    public String generate(TenantContext tenant, List<String> tableIds) {
        tenant.require(Permission.MANAGE_TABLES);

        List<DiningTable> selected = (tableIds == null || tableIds.isEmpty())
                ? tables.findByHotelIdAndActiveTrue(tenant.hotelId())
                : tableIds.stream().distinct()
                        .map(id -> tables.findByIdAndHotelId(id, tenant.hotelId())
                                .orElseThrow(() -> DomainException.notFound("Table")))
                        .toList();

        if (selected.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "There are no tables to print");
        }
        if (selected.size() > MAX_TABLES) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "Select at most " + MAX_TABLES + " tables per sheet");
        }
        if (selected.stream().anyMatch(t -> !t.isActive())) {
            throw DomainException.conflict("A deactivated table cannot get a QR code");
        }

        List<QrSheetRenderer.Card> cards = selected.stream()
                .sorted(Comparator.comparing(DiningTable::getNumber, TableNumbers::compare))
                .map(table -> {
                    IssuedQrResponse issued = qrCredentials.issueForTable(tenant, table);
                    return new QrSheetRenderer.Card(table.getNumber(), table.getSection(), issued.qrImage());
                })
                .toList();

        return QrSheetRenderer.render(hotelService.get(tenant).name(), cards);
    }
}