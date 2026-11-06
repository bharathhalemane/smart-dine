package com.example.qrorder.dining.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.dining.dto.FloorTableResponse;
import com.example.qrorder.dining.dto.SessionDetailResponse;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.model.TableState;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import com.example.qrorder.tables.dto.TableResponse;
import com.example.qrorder.tables.service.TableService;
import com.example.qrorder.tenancy.context.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class SessionQueryService {

    private final DiningSessionRepository sessions;
    private final TableService tables;

    public SessionQueryService(DiningSessionRepository sessions, TableService tables) {
        this.sessions = sessions;
        this.tables = tables;
    }

    /** Every active table with its derived state. Any member of the hotel may look. */
    public List<FloorTableResponse> floor(TenantContext tenant) {
        Map<String, DiningSession> activeByTable = sessions.findByHotelIdAndActiveTrue(tenant.hotelId()).stream()
                .collect(Collectors.toMap(DiningSession::getTableId, Function.identity(), (a, b) -> a));

        return tables.list(tenant, false).stream() // already in natural order: T2 before T10
                .map(table -> toRow(table, activeByTable.get(table.id())))
                .toList();
    }

    public SessionDetailResponse get(TenantContext tenant, String sessionId) {
        return sessions.findByIdAndHotelId(sessionId, tenant.hotelId())
                .map(SessionDetailResponse::from)
                .orElseThrow(() -> DomainException.notFound("Session"));
    }

    private static FloorTableResponse toRow(TableResponse table, DiningSession session) {
        return new FloorTableResponse(table.id(), table.number(), table.capacity(), table.section(),
                TableState.of(session == null ? null : session.getStatus()),
                session == null ? null : session.getId(),
                session == null ? null : session.getOpenedAt());
    }
}