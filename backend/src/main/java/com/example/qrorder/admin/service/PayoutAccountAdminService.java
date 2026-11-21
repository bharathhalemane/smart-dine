package com.example.qrorder.admin.service;

import com.example.qrorder.admin.dto.PayoutAccountResponse;
import com.example.qrorder.admin.model.AdminAuditAction;
import com.example.qrorder.admin.model.AdminAuditEntry;
import com.example.qrorder.common.error.CorrelationIdFilter;
import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.identity.service.AccountDirectory;
import com.example.qrorder.identity.service.AccountService;
import com.example.qrorder.identity.service.AccountSummary;
import com.example.qrorder.payout.model.HotelPayoutAccount;
import com.example.qrorder.payout.service.PayoutService;
import com.example.qrorder.tenancy.model.Hotel;
import com.mongodb.MongoException;
import java.time.Clock;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** Platform admins link a hotel to its Razorpay linked account. Waiting transfers are released when it is set. */
@Service
public class PayoutAccountAdminService {

    private final MongoTemplate mongo;
    private final PayoutAccountWriter writer;
    private final PayoutService payouts;
    private final AccountDirectory directory;
    private final AccountService accounts;
    private final Clock clock;

    public PayoutAccountAdminService(MongoTemplate mongo, PayoutAccountWriter writer, PayoutService payouts,
                                     AccountDirectory directory, AccountService accounts, Clock clock) {
        this.mongo = mongo;
        this.writer = writer;
        this.payouts = payouts;
        this.directory = directory;
        this.accounts = accounts;
        this.clock = clock;
    }

    public PayoutAccountResponse get(String hotelId) {
        load(hotelId);
        return new PayoutAccountResponse(hotelId, current(hotelId));
    }

    public PayoutAccountResponse update(String actorUserId, String hotelId, String linkedAccountId, String rawReason) {
        if (!directory.isActivePlatformAdmin(actorUserId)) { // asked fresh: a removed admin loses this at once
            throw new DomainException(ErrorCode.FORBIDDEN, "You do not have permission to do this");
        }
        String reason = rawReason == null ? "" : rawReason.strip();
        if (reason.length() < 3 || reason.length() > 300) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "Give a reason of 3 to 300 characters");
        }
        String account = linkedAccountId == null ? "" : linkedAccountId.strip();
        if (!account.matches("acc_[A-Za-z0-9]{8,40}")) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "The linked account id must look like acc_XXXXXXXXXXXXXX");
        }
        Hotel hotel = load(hotelId);
        String old = current(hotelId);
        if (account.equals(old)) {
            return new PayoutAccountResponse(hotelId, old); // nothing to change, nothing to audit
        }

        AccountSummary actor = accounts.summaries(Set.of(actorUserId)).get(actorUserId);
        AdminAuditEntry audit = new AdminAuditEntry(clock.instant(), actorUserId,
                actor == null ? null : actor.email(), actor == null ? null : actor.name(),
                AdminAuditAction.HOTEL_PAYOUT_ACCOUNT_CHANGED, "HOTEL", hotel.getId(),
                hotel.getName() + " (" + hotel.getSlug() + ")", old == null ? "none" : old, account, reason,
                MDC.get(CorrelationIdFilter.MDC_KEY));
        try {
            writer.apply(hotelId, account, actorUserId, audit);
        } catch (RuntimeException e) {
            if (isConflict(e)) {
                throw DomainException.conflict("The payout account was just changed by someone else. Reload and try again");
            }
            throw e;
        }
        releaseWaiting(hotelId);
        return new PayoutAccountResponse(hotelId, account);
    }

    /** Transfers that were waiting only for this account are sent now. A failure here does not undo the change. */
    private void releaseWaiting(String hotelId) {
        try {
            mongo.find(Query.query(Criteria.where("hotelId").is(hotelId).and("status").is("PENDING")),
                    com.example.qrorder.payout.model.PayoutTransfer.class).forEach(t -> payouts.sendOne(t.getId()));
        } catch (RuntimeException ignored) {
            // the sweeper in step 2b picks them up
        }
    }

    private String current(String hotelId) {
        HotelPayoutAccount account = mongo.findOne(Query.query(Criteria.where("hotelId").is(hotelId)), HotelPayoutAccount.class);
        return account == null ? null : account.getLinkedAccountId();
    }

    private Hotel load(String hotelId) {
        Hotel hotel = mongo.findById(hotelId, Hotel.class);
        if (hotel == null) {
            throw DomainException.notFound("Hotel");
        }
        return hotel;
    }

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