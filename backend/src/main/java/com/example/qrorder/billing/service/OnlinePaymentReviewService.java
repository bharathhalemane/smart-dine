package com.example.qrorder.billing.service;

import com.example.qrorder.billing.dto.OnlinePaymentView;
import com.example.qrorder.billing.model.OnlinePayment;
import com.example.qrorder.billing.model.OnlinePaymentStatus;
import com.example.qrorder.billing.repository.OnlinePaymentRepository;
import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import java.time.Clock;
import java.util.List;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** Online payments that took money we could not count: someone has to refund or decide. */
@Service
public class OnlinePaymentReviewService {

    private final OnlinePaymentRepository attempts;
    private final MongoTemplate mongo;
    private final Clock clock;

    public OnlinePaymentReviewService(OnlinePaymentRepository attempts, MongoTemplate mongo, Clock clock) {
        this.attempts = attempts;
        this.mongo = mongo;
        this.clock = clock;
    }

    public List<OnlinePaymentView> needingReview(TenantContext tenant) {
        tenant.require(Permission.TAKE_PAYMENT);
        return attempts.findByHotelIdAndStatusOrderByUpdatedAtAscIdAsc(tenant.hotelId(),
                OnlinePaymentStatus.NEEDS_REVIEW).stream().map(OnlinePaymentView::from).toList();
    }

    public OnlinePaymentView resolve(TenantContext tenant, String attemptId, String note) {
        tenant.require(Permission.ADJUST_BILL);
        OnlinePayment resolved = mongo.findAndModify(
                Query.query(Criteria.where("id").is(attemptId).and("hotelId").is(tenant.hotelId())
                        .and("status").is(OnlinePaymentStatus.NEEDS_REVIEW)),
                new Update().set("status", OnlinePaymentStatus.RESOLVED).set("resolutionNote", note.strip())
                        .set("resolvedByUserId", tenant.userId()).set("updatedAt", clock.instant()),
                FindAndModifyOptions.options().returnNew(true), OnlinePayment.class);
        if (resolved != null) {
            return OnlinePaymentView.from(resolved);
        }
        OnlinePayment current = attempts.findById(attemptId)
                .filter(a -> a.getHotelId().equals(tenant.hotelId()))
                .orElseThrow(() -> DomainException.notFound("Payment"));
        if (current.getStatus() == OnlinePaymentStatus.RESOLVED) {
            return OnlinePaymentView.from(current); // already done: repeating it changes nothing
        }
        throw DomainException.conflict("This payment is not waiting for review");
    }
}