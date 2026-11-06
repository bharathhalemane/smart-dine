package com.example.qrorder.tables.service;

import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.tables.dto.ResolvedTable;
import com.example.qrorder.tables.model.DiningTable;
import com.example.qrorder.tables.model.QrStatus;
import com.example.qrorder.tables.repository.DiningTableRepository;
import com.example.qrorder.tables.repository.QrCredentialRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class QrResolveService {

    private final QrCredentialRepository credentials;
    private final DiningTableRepository tables;

    public QrResolveService(QrCredentialRepository credentials, DiningTableRepository tables) {
        this.credentials = credentials;
        this.tables = tables;
    }

    /** Empty for an unknown or revoked token or a deactivated table: callers must not say which. */
    public Optional<ResolvedTable> resolve(String plainToken) {
        return credentials.findByTokenHash(Tokens.sha256(plainToken))
                .filter(c -> c.getStatus() == QrStatus.ACTIVE)
                .flatMap(c -> tables.findByIdAndHotelId(c.getTableId(), c.getHotelId()))
                .filter(DiningTable::isActive)
                .map(t -> new ResolvedTable(t.getHotelId(), t.getId(), t.getNumber(), t.getSection()));
    }
}