package com.example.qrorder.admin.service;

import com.example.qrorder.admin.dto.CommissionResponse;
import com.example.qrorder.admin.model.AdminAuditAction;
import com.example.qrorder.admin.model.AdminAuditEntry;
import com.example.qrorder.common.error.CorrelationIdFilter;
import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.earnings.repository.EarningsSettingsRepository;
import com.example.qrorder.earnings.service.EarningsLedger;
import com.example.qrorder.identity.service.AccountDirectory;
import com.example.qrorder.identity.service.AccountService;
import com.example.qrorder.identity.service.AccountSummary;
import com.example.qrorder.tenancy.model.Hotel;
import com.mongodb.MongoException;
import java.time.Clock;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/** Reads and sets the commission rate the platform charges a hotel. Platform admins only. */
@Service
public class CommissionAdminService {

    private final MongoTemplate mongo;
    private final EarningsSettingsRepository settings;
    private final EarningsLedger ledger;
    private final CommissionWriter writer;
    private final AccountDirectory directory;
    private final AccountService accounts;
    private final Clock clock;

    public CommissionAdminService(MongoTemplate mongo, EarningsSettingsRepository settings, EarningsLedger ledger,
                                  CommissionWriter writer, AccountDirectory directory, AccountService accounts,
                                  Clock clock) {
        this.mongo = mongo;
        this.settings = settings;
        this.ledger = ledger;
        this.writer = writer;
        this.directory = directory;
        this.accounts = accounts;
        this.clock = clock;
    }

    public CommissionResponse get(String hotelId) {
        load(hotelId);
        return new CommissionResponse(hotelId, ledger.commissionBps(hotelId), settings.existsByHotelId(hotelId));
    }

    public CommissionResponse update(String actorUserId, String hotelId, int commissionBps, String rawReason) {
        if (!directory.isActivePlatformAdmin(actorUserId)) { // asked fresh: a removed admin loses this at once
            throw new DomainException(ErrorCode.FORBIDDEN, "You do not have permission to do this");
        }
        String reason = rawReason == null ? "" : rawReason.strip();
        if (reason.length() < 3 || reason.length() > 300) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "Give a reason of 3 to 300 characters");
        }
        if (commissionBps < 0 || commissionBps > 3000) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "The commission must be between 0 and 3000 basis points");
        }
        Hotel hotel = load(hotelId);
        int old = ledger.commissionBps(hotelId);
        if (old == commissionBps && settings.existsByHotelId(hotelId)) {
            return new CommissionResponse(hotelId, old, true); // nothing to change, nothing to audit
        }

        AccountSummary actor = accounts.summaries(Set.of(actorUserId)).get(actorUserId);
        AdminAuditEntry audit = new AdminAuditEntry(clock.instant(), actorUserId,
                actor == null ? null : actor.email(), actor == null ? null : actor.name(),
                AdminAuditAction.HOTEL_COMMISSION_CHANGED, "HOTEL", hotel.getId(),
                hotel.getName() + " (" + hotel.getSlug() + ")", old + " bps", commissionBps + " bps", reason,
                MDC.get(CorrelationIdFilter.MDC_KEY));
        try {
            writer.apply(hotelId, commissionBps, actorUserId, audit);
        } catch (RuntimeException e) {
            if (isConflict(e)) {
                throw DomainException.conflict("The commission was just changed by someone else. Reload and try again");
            }
            throw e;
        }
        return new CommissionResponse(hotelId, commissionBps, true);
    }

    private Hotel load(String hotelId) {
        Hotel hotel = mongo.findById(hotelId, Hotel.class);
        if (hotel == null) {
            throw DomainException.notFound("Hotel");
        }
        return hotel;
    }

    /** Two admins writing at once: MongoDB aborts the second transaction (write conflict, or a duplicate on first create). */
    private static boolean isConflict(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof DuplicateKeyException) {
                return true;
            }
            if (t instanceof MongoException mongoError && (mongoError.getCode() == 112
                    || mongoError.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL))) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}