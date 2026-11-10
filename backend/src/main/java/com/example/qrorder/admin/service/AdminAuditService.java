package com.example.qrorder.admin.service;

import com.example.qrorder.admin.dto.AdminAuditView;
import com.example.qrorder.admin.model.AdminAuditAction;
import com.example.qrorder.admin.model.AdminAuditEntry;
import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.common.page.PageResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** Read-only view of the admin audit log. Entries are never changed or deleted. */
@Service
public class AdminAuditService {

    private static final int MAX_SIZE = 100;

    private final MongoTemplate mongo;

    public AdminAuditService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public PageResponse<AdminAuditView> search(String targetId, String actorUserId, String action,
                                               Instant from, Instant to, int page, int size) {
        if (page < 0) {
            throw invalid("page must be 0 or more");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw invalid("size must be between 1 and " + MAX_SIZE);
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw invalid("from must not be after to");
        }

        List<Criteria> filters = new ArrayList<>();
        if (notBlank(targetId)) {
            filters.add(Criteria.where("targetId").is(targetId.strip()));
        }
        if (notBlank(actorUserId)) {
            filters.add(Criteria.where("actorUserId").is(actorUserId.strip()));
        }
        if (notBlank(action)) {
            filters.add(Criteria.where("action").is(parse(action.strip())));
        }
        if (from != null) {
            filters.add(Criteria.where("at").gte(from));
        }
        if (to != null) {
            filters.add(Criteria.where("at").lte(to));
        }
        Query filter = filters.isEmpty() ? new Query()
                : Query.query(new Criteria().andOperator(filters.toArray(new Criteria[0])));

        long total = mongo.count(filter, AdminAuditEntry.class);
        Query pageQuery = Query.of(filter).with(Sort.by(Sort.Order.desc("at"), Sort.Order.desc("id")))
                .skip((long) page * size).limit(size);
        List<AdminAuditView> items = mongo.find(pageQuery, AdminAuditEntry.class).stream()
                .map(AdminAuditView::of).toList();
        return new PageResponse<>(items, page, size, total, (int) Math.ceil(total / (double) size));
    }

    private static AdminAuditAction parse(String action) {
        try {
            return AdminAuditAction.valueOf(action);
        } catch (IllegalArgumentException e) {
            throw invalid("Unknown action. Use one of: HOTEL_SUSPENDED, HOTEL_REACTIVATED, HOTEL_DEACTIVATED");
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static DomainException invalid(String message) {
        return new DomainException(ErrorCode.VALIDATION_FAILED, message);
    }
}