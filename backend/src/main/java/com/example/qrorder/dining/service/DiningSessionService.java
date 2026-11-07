package com.example.qrorder.dining.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.dining.dto.ScanResponse;
import com.example.qrorder.dining.dto.SessionResponse;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import com.example.qrorder.tables.dto.ResolvedTable;
import com.example.qrorder.tables.service.ActiveSessionGuard;
import com.example.qrorder.tables.service.QrResolveService;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.dto.PublicHotelInfo;
import com.example.qrorder.tenancy.service.HotelLookupService;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class DiningSessionService implements ActiveSessionGuard {

    private static final int MAX_ATTEMPTS = 3;

    private final DiningSessionRepository sessions;
    private final QrResolveService qrResolver;
    private final HotelLookupService hotels;
    private final Clock clock;

    public DiningSessionService(DiningSessionRepository sessions, QrResolveService qrResolver,
                                HotelLookupService hotels, Clock clock) {
        this.sessions = sessions;
        this.qrResolver = qrResolver;
        this.hotels = hotels;
        this.clock = clock;
    }

    public record OpenResult(DiningSession session, boolean opened) {}

    public record ScannedTable(ScanResponse response, DiningSession session) {}

    public ScanResponse scan(String token) {
        return scanTable(token).response();
    }

    public ScannedTable scanTable(String token) {
        ResolvedTable table = qrResolver.resolve(token).orElseThrow(DiningSessionService::invalidQr);
        PublicHotelInfo hotel = hotels.findActive(table.hotelId()).orElseThrow(DiningSessionService::invalidQr);

        OpenResult result = openOrJoin(table.hotelId(), table.tableId(), table.number());
        return new ScannedTable(new ScanResponse(hotel.name(), table.number(), table.section(),
                result.session().getStatus(), result.opened()), result.session());
    }

    /**
     * Opens a session for the table, or returns the one already open. Safe under concurrency:
     * if two scans race, the unique index lets one insert win, and the loser reads the winner's session.
     */
    public OpenResult openOrJoin(String hotelId, String tableId, String tableNumber) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Optional<DiningSession> existing = sessions.findByTableIdAndActiveTrue(tableId);
            if (existing.isPresent()) {
                return new OpenResult(existing.get(), false);
            }
            try {
                return new OpenResult(sessions.save(new DiningSession(hotelId, tableId, tableNumber, clock.instant())), true);
            } catch (DuplicateKeyException e) {
                // Someone opened it between our read and our insert. Loop: the next read finds it.
            }
        }
        throw DomainException.conflict("Could not open the table session, please try again");
    }

    /** Any member of the hotel may see which tables are in use. */
    public List<SessionResponse> listActive(TenantContext tenant) {
        return sessions.findByHotelIdAndActiveTrue(tenant.hotelId()).stream()
                .sorted(Comparator.comparing(DiningSession::getOpenedAt))
                .map(SessionResponse::from)
                .toList();
    }

    @Override
    public boolean hasActiveSession(String hotelId, String tableId) {
        return sessions.findByTableIdAndActiveTrue(tableId)
                .filter(s -> s.getHotelId().equals(hotelId))
                .isPresent();
    }

    private static DomainException invalidQr() {
        return DomainException.notFound("QR code");
    }
}