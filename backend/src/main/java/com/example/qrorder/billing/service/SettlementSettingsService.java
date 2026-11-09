package com.example.qrorder.billing.service;

import com.example.qrorder.billing.dto.SettlementSettingsResponse;
import com.example.qrorder.billing.model.SettlementSettings;
import com.example.qrorder.billing.repository.SettlementSettingsRepository;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import java.time.Clock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class SettlementSettingsService {

    private final SettlementSettingsRepository repository;
    private final MongoTemplate mongo;
    private final Clock clock;

    public SettlementSettingsService(SettlementSettingsRepository repository, MongoTemplate mongo, Clock clock) {
        this.repository = repository;
        this.mongo = mongo;
        this.clock = clock;
    }

    /** Off until the owner turns it on. */
    public boolean required(String hotelId) {
        return repository.findByHotelId(hotelId).map(SettlementSettings::isRequireSettlementBeforeClear).orElse(false);
    }

    public SettlementSettingsResponse get(TenantContext tenant) {
        tenant.requireAnyPermission(Permission.MANAGE_HOTEL, Permission.MANAGE_TABLES);
        return new SettlementSettingsResponse(required(tenant.hotelId()));
    }

    public SettlementSettingsResponse update(TenantContext tenant, boolean require) {
        tenant.require(Permission.MANAGE_HOTEL);
        Query query = Query.query(Criteria.where("hotelId").is(tenant.hotelId()));
        Update update = new Update().set("requireSettlementBeforeClear", require)
                .set("updatedBy", tenant.userId()).set("updatedAt", clock.instant());
        try {
            mongo.upsert(query, update, SettlementSettings.class);
        } catch (DuplicateKeyException race) {
            mongo.upsert(query, update, SettlementSettings.class); // another upsert created it first
        }
        return new SettlementSettingsResponse(require);
    }
}