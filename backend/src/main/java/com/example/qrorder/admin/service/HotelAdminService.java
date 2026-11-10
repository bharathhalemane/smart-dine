package com.example.qrorder.admin.service;

import com.example.qrorder.admin.dto.AdminHotelDetail;
import com.example.qrorder.admin.model.AdminAuditAction;
import com.example.qrorder.admin.model.AdminAuditEntry;
import com.example.qrorder.common.error.CorrelationIdFilter;
import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.identity.service.AccountDirectory;
import com.example.qrorder.identity.service.AccountService;
import com.example.qrorder.identity.service.AccountSummary;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelStatus;
import com.mongodb.MongoException;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/** Suspend, reactivate and deactivate a hotel, each with a recorded reason. */
@Service
public class HotelAdminService {

    private static final Logger log = LoggerFactory.getLogger(HotelAdminService.class);

    /** Where each status may go. Anything else is refused. */
    private static final Map<HotelStatus, Set<HotelStatus>> ALLOWED = Map.of(
            HotelStatus.ACTIVE, Set.of(HotelStatus.SUSPENDED, HotelStatus.DEACTIVATED),
            HotelStatus.SUSPENDED, Set.of(HotelStatus.ACTIVE, HotelStatus.DEACTIVATED),
            HotelStatus.DEACTIVATED, Set.of(HotelStatus.ACTIVE));

    private final MongoTemplate mongo;
    private final HotelStatusWriter writer;
    private final AccountDirectory directory;
    private final AccountService accounts;
    private final AdminHotelService details;
    private final Clock clock;

    public HotelAdminService(MongoTemplate mongo, HotelStatusWriter writer, AccountDirectory directory,
                             AccountService accounts, AdminHotelService details, Clock clock) {
        this.mongo = mongo;
        this.writer = writer;
        this.directory = directory;
        this.accounts = accounts;
        this.details = details;
        this.clock = clock;
    }

    public AdminHotelDetail suspend(String actorUserId, String hotelId, String reason) {
        return change(actorUserId, hotelId, HotelStatus.SUSPENDED, AdminAuditAction.HOTEL_SUSPENDED, reason);
    }

    public AdminHotelDetail reactivate(String actorUserId, String hotelId, String reason) {
        return change(actorUserId, hotelId, HotelStatus.ACTIVE, AdminAuditAction.HOTEL_REACTIVATED, reason);
    }

    public AdminHotelDetail deactivate(String actorUserId, String hotelId, String reason, String confirmSlug) {
        requireAdmin(actorUserId);
        Hotel hotel = load(hotelId);
        if (confirmSlug == null || !hotel.getSlug().equals(confirmSlug.strip())) {
            throw invalid("To deactivate this hotel, confirmSlug must be exactly: " + hotel.getSlug());
        }
        return change(actorUserId, hotelId, HotelStatus.DEACTIVATED, AdminAuditAction.HOTEL_DEACTIVATED, reason);
    }

    private AdminHotelDetail change(String actorUserId, String hotelId, HotelStatus to, AdminAuditAction action,
                                    String rawReason) {
        requireAdmin(actorUserId);
        String reason = rawReason == null ? "" : rawReason.strip();
        if (reason.length() < 3 || reason.length() > 300) {
            throw invalid("Give a reason of 3 to 300 characters");
        }

        Hotel hotel = load(hotelId);
        HotelStatus from = hotel.getStatus();
        if (from == to) {
            throw DomainException.invalidTransition("This hotel is already " + to.name().toLowerCase());
        }
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw DomainException.invalidTransition("A " + from.name().toLowerCase() + " hotel cannot be made "
                    + to.name().toLowerCase());
        }

        AccountSummary actor = accounts.summaries(Set.of(actorUserId)).get(actorUserId);
        AdminAuditEntry audit = new AdminAuditEntry(clock.instant(), actorUserId,
                actor == null ? null : actor.email(), actor == null ? null : actor.name(), action, "HOTEL",
                hotel.getId(), hotel.getName() + " (" + hotel.getSlug() + ")", from.name(), to.name(), reason,
                MDC.get(CorrelationIdFilter.MDC_KEY));

        Hotel updated;
        try {
            updated = writer.apply(hotelId, from, to, to == HotelStatus.ACTIVE ? null : reason, audit);
        } catch (RuntimeException e) {
            if (isWriteConflict(e)) { // the other admin's change got there first
                throw DomainException.conflict("This hotel was just changed by someone else. Reload and try again");
            }
            throw e;
        }
        if (updated == null) {
            throw DomainException.conflict("This hotel was just changed by someone else. Reload and try again");
        }
        log.info("Platform admin {} changed hotel {} from {} to {}", actorUserId, hotelId, from, to);
        return details.get(hotelId);
    }

    /** MongoDB aborts the second of two concurrent transactions on one document instead of making it wait. */
    private static boolean isWriteConflict(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof MongoException mongo
                    && (mongo.getCode() == 112 || mongo.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL))) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private void requireAdmin(String actorUserId) {
        if (!directory.isActivePlatformAdmin(actorUserId)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "You do not have permission to do this");
        }
    }

    private Hotel load(String hotelId) {
        Hotel hotel = mongo.findById(hotelId, Hotel.class);
        if (hotel == null) {
            throw DomainException.notFound("Hotel");
        }
        return hotel;
    }

    private static DomainException invalid(String message) {
        return new DomainException(ErrorCode.VALIDATION_FAILED, message);
    }
}