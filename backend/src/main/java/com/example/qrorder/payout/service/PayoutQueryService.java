package com.example.qrorder.payout.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.common.page.PageResponse;
import com.example.qrorder.payout.dto.PayoutTransferView;
import com.example.qrorder.payout.model.PayoutTransfer;
import com.example.qrorder.payout.model.TransferStatus;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service
public class PayoutQueryService {

    private static final int MAX_SIZE = 100;

    private final MongoTemplate mongo;

    public PayoutQueryService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** A hotel owner's own transfers, newest first. */
    public PageResponse<PayoutTransferView> forHotel(TenantContext tenant, TransferStatus status, int page, int size) {
        tenant.require(Permission.MANAGE_HOTEL);
        return find(tenant.hotelId(), status, page, size);
    }

    /** Platform admin: every hotel's transfers. */
    public PageResponse<PayoutTransferView> all(TransferStatus status, int page, int size) {
        return find(null, status, page, size);
    }

    private PageResponse<PayoutTransferView> find(String hotelId, TransferStatus status, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "page must be 0 or more and size between 1 and " + MAX_SIZE);
        }
        Criteria criteria = new Criteria();
        if (hotelId != null) {
            criteria = criteria.and("hotelId").is(hotelId);
        }
        if (status != null) {
            criteria = criteria.and("status").is(status);
        }
        Query filter = Query.query(criteria);
        long total = mongo.count(filter, PayoutTransfer.class);
        List<PayoutTransferView> items = mongo.find(Query.of(filter)
                        .with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))
                        .skip((long) page * size).limit(size), PayoutTransfer.class)
                .stream().map(PayoutTransferView::of).toList();
        return PageResponse.of(items, page, size, total);
    }
}