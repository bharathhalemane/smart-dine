package com.example.qrorder.tables.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.tables.config.QrProperties;
import com.example.qrorder.tables.dto.IssuedQrResponse;
import com.example.qrorder.tables.dto.QrStatusResponse;
import com.example.qrorder.tables.model.DiningTable;
import com.example.qrorder.tables.model.QrCredential;
import com.example.qrorder.tables.model.QrStatus;
import com.example.qrorder.tables.repository.DiningTableRepository;
import com.example.qrorder.tables.repository.QrCredentialRepository;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import java.time.Clock;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QrCredentialService {

    private final DiningTableRepository tables;
    private final QrCredentialRepository credentials;
    private final QrProperties props;
    private final Clock clock;

    public QrCredentialService(DiningTableRepository tables, QrCredentialRepository credentials,
                               QrProperties props, Clock clock) {
        this.tables = tables;
        this.credentials = credentials;
        this.props = props;
        this.clock = clock;
    }

    /** Issues a credential. If one is already active it is revoked first, in the same transaction (rotation). */
    @Transactional
    public IssuedQrResponse issue(TenantContext tenant, String tableId) {
        tenant.require(Permission.MANAGE_TABLES);
        DiningTable table = findTable(tenant, tableId);
        if (!table.isActive()) {
            throw DomainException.conflict("This table is deactivated");
        }

        revokeActive(tenant.hotelId(), tableId, tenant.userId());

        String plainToken = Tokens.newToken();
        Instant now = clock.instant();
        try {
            QrCredential saved = credentials.save(new QrCredential(tenant.hotelId(), tableId,
                    Tokens.sha256(plainToken), tenant.userId(), now));
            return new IssuedQrResponse(saved.getId(), tableId, plainToken,
                    props.customerBaseUrl() + "/t/" + plainToken, now);
        } catch (DuplicateKeyException e) {
            // Two managers issued at the same moment; the unique index let only one through.
            throw DomainException.conflict("The QR code was changed at the same time, please try again");
        }
    }

    public QrStatusResponse status(TenantContext tenant, String tableId) {
        tenant.require(Permission.MANAGE_TABLES);
        findTable(tenant, tableId);
        return credentials.findByHotelIdAndTableIdAndStatus(tenant.hotelId(), tableId, QrStatus.ACTIVE)
                .map(c -> new QrStatusResponse(true, c.getId(), c.getCreatedAt()))
                .orElse(new QrStatusResponse(false, null, null));
    }

    /** Idempotent: revoking a table that has no active credential is not an error. */
    @Transactional
    public void revoke(TenantContext tenant, String tableId) {
        tenant.require(Permission.MANAGE_TABLES);
        findTable(tenant, tableId);
        revokeActive(tenant.hotelId(), tableId, tenant.userId());
    }

    /** No permission check: callers (e.g. table deactivation) have already authorized the action. */
    public void revokeActive(String hotelId, String tableId, String byUserId) {
        credentials.findByHotelIdAndTableIdAndStatus(hotelId, tableId, QrStatus.ACTIVE).ifPresent(c -> {
            c.revoke(byUserId, clock.instant());
            credentials.save(c);
        });
    }

    private DiningTable findTable(TenantContext tenant, String tableId) {
        return tables.findByIdAndHotelId(tableId, tenant.hotelId())
                .orElseThrow(() -> DomainException.notFound("Table"));
    }
}