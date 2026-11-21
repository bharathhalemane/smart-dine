package com.example.qrorder.admin.service;

import com.example.qrorder.admin.model.AdminAuditAction;
import com.example.qrorder.admin.model.AdminAuditEntry;
import com.example.qrorder.common.error.CorrelationIdFilter;
import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.identity.service.AccountDirectory;
import com.example.qrorder.identity.service.AccountService;
import com.example.qrorder.identity.service.AccountSummary;
import com.example.qrorder.payout.dto.PayoutTransferView;
import com.example.qrorder.payout.model.PayoutTransfer;
import com.example.qrorder.payout.model.TransferStatus;
import com.example.qrorder.payout.service.PayoutService;
import java.time.Clock;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/** Retrying a transfer sends money, so: a fresh admin check, a reason, an audit entry, and a confirmation when unsure. */
@Service
public class PayoutRetryService {

    private final MongoTemplate mongo;
    private final PayoutRetryWriter writer;
    private final PayoutService payouts;
    private final AccountDirectory directory;
    private final AccountService accounts;
    private final Clock clock;

    public PayoutRetryService(MongoTemplate mongo, PayoutRetryWriter writer, PayoutService payouts,
                              AccountDirectory directory, AccountService accounts, Clock clock) {
        this.mongo = mongo;
        this.writer = writer;
        this.payouts = payouts;
        this.directory = directory;
        this.accounts = accounts;
        this.clock = clock;
    }

    public PayoutTransferView retry(String actorUserId, String transferId, String rawReason, boolean confirmNotSent) {
        if (!directory.isActivePlatformAdmin(actorUserId)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "You do not have permission to do this");
        }
        String reason = rawReason == null ? "" : rawReason.strip();
        if (reason.length() < 3 || reason.length() > 300) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "Give a reason of 3 to 300 characters");
        }
        PayoutTransfer transfer = mongo.findById(transferId, PayoutTransfer.class);
        if (transfer == null) {
            throw DomainException.notFound("Transfer");
        }
        TransferStatus from = transfer.getStatus();
        if (from != TransferStatus.FAILED && from != TransferStatus.NEEDS_REVIEW) {
            throw DomainException.conflict("Only a failed transfer, or one that needs review, can be retried");
        }
        if (from == TransferStatus.NEEDS_REVIEW && !confirmNotSent) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "Check the Razorpay dashboard first. If nothing was transferred, send confirmNotSent=true");
        }

        AccountSummary actor = accounts.summaries(Set.of(actorUserId)).get(actorUserId);
        AdminAuditEntry audit = new AdminAuditEntry(clock.instant(), actorUserId,
                actor == null ? null : actor.email(), actor == null ? null : actor.name(),
                AdminAuditAction.PAYOUT_RETRIED, "HOTEL", transfer.getHotelId(), "Transfer " + transfer.getId(),
                from.name(), TransferStatus.PENDING.name(), reason, MDC.get(CorrelationIdFilter.MDC_KEY));
        writer.reset(transferId, from, audit);
        return PayoutTransferView.of(payouts.sendOne(transferId));
    }
}