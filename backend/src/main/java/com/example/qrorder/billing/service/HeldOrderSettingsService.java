package com.example.qrorder.billing.service;

import com.example.qrorder.billing.dto.HoldSettingsResponse;
import com.example.qrorder.billing.model.HeldOrderSettings;
import com.example.qrorder.billing.repository.HeldOrderSettingsRepository;
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
public class HeldOrderSettingsService {

    private final HeldOrderSettingsRepository repository;
    private final MongoTemplate mongo;
    private final Clock clock;

    public HeldOrderSettingsService(HeldOrderSettingsRepository repository, MongoTemplate mongo, Clock clock) {
        this.repository = repository;
        this.mongo = mongo;
        this.clock = clock;
    }

    /** The hotel's own choice, or 15 minutes. */
    public int minutes(String hotelId) {
        return repository.findByHotelId(hotelId).map(HeldOrderSettings::getHoldMinutes)
                .orElse(HeldOrderSettings.DEFAULT_MINUTES);
    }

    public HoldSettingsResponse get(TenantContext tenant) {
        tenant.requireAnyPermission(Permission.TAKE_PAYMENT, Permission.MANAGE_HOTEL);
        return new HoldSettingsResponse(minutes(tenant.hotelId()));
    }

    public HoldSettingsResponse update(TenantContext tenant, int holdMinutes) {
        tenant.require(Permission.MANAGE_HOTEL);
        Query query = Query.query(Criteria.where("hotelId").is(tenant.hotelId()));
        Update update = new Update().set("holdMinutes", holdMinutes)
                .set("updatedBy", tenant.userId()).set("updatedAt", clock.instant());
        try {
            mongo.upsert(query, update, HeldOrderSettings.class);
        } catch (DuplicateKeyException race) {
            mongo.upsert(query, update, HeldOrderSettings.class); // another upsert created it first
        }
        return new HoldSettingsResponse(holdMinutes);
    }
}