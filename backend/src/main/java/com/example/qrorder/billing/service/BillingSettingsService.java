package com.example.qrorder.billing.service;

import com.example.qrorder.billing.dto.BillingSettingsResponse;
import com.example.qrorder.billing.dto.UpdateBillingSettingsRequest;
import com.example.qrorder.billing.model.BillingSettings;
import com.example.qrorder.billing.repository.BillingSettingsRepository;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import java.time.Clock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class BillingSettingsService {

    private final BillingSettingsRepository repository;
    private final MongoTemplate mongo;
    private final Clock clock;

    public BillingSettingsService(BillingSettingsRepository repository, MongoTemplate mongo, Clock clock) {
        this.repository = repository;
        this.mongo = mongo;
        this.clock = clock;
    }

    /** What applies to this hotel right now: its own settings, or "no tax, no service charge". */
    public BillingSettings effective(String hotelId) {
        return repository.findByHotelId(hotelId).orElseGet(() -> BillingSettings.defaults(hotelId));
    }

    public BillingSettingsResponse get(TenantContext tenant) {
        tenant.requireAnyPermission(Permission.TAKE_PAYMENT, Permission.MANAGE_HOTEL);
        return BillingSettingsResponse.from(effective(tenant.hotelId()));
    }

    public BillingSettingsResponse update(TenantContext tenant, UpdateBillingSettingsRequest request) {
        tenant.require(Permission.MANAGE_HOTEL);
        for (int attempt = 0; ; attempt++) {
            try {
                BillingSettings saved = mongo.findAndModify(
                        Query.query(Criteria.where("hotelId").is(tenant.hotelId())),
                        new Update()
                                .set("taxName", request.taxName().strip())
                                .set("taxRateBps", request.taxRateBps())
                                .set("taxInclusive", request.taxInclusive())
                                .set("serviceChargeBps", request.serviceChargeBps())
                                .set("updatedByUserId", tenant.userId())
                                .set("updatedAt", clock.instant()),
                        FindAndModifyOptions.options().upsert(true).returnNew(true), BillingSettings.class);
                return BillingSettingsResponse.from(saved);
            } catch (DuplicateKeyException firstInsertCollided) {
                if (attempt >= 1) {
                    throw firstInsertCollided; // the second try takes the update path, so this is not expected
                }
            }
        }
    }
}