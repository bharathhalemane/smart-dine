package com.example.qrorder.billing.service;

import com.example.qrorder.billing.dto.PaymentSettingsResponse;
import com.example.qrorder.billing.dto.UpdatePaymentSettingsRequest;
import com.example.qrorder.billing.model.PaymentMethod;
import com.example.qrorder.billing.model.PaymentSettings;
import com.example.qrorder.billing.repository.PaymentSettingsRepository;
import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class PaymentSettingsService {

    /** name@bank: a short handle of letters, digits, dot, underscore or hyphen, then the bank's handle. */
    private static final Pattern UPI_ID = Pattern.compile("^[A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9]{1,31}$");

    private final PaymentSettingsRepository repository;
    private final ProviderConfigService providers;
    private final MongoTemplate mongo;
    private final Clock clock;

    public PaymentSettingsService(PaymentSettingsRepository repository, MongoTemplate mongo, Clock clock,
                                  ProviderConfigService providers) {
        this.repository = repository;
        this.mongo = mongo;
        this.providers = providers;
        this.clock = clock;
    }

    /** What applies to this hotel right now: its own settings, or "cash only". */
    public PaymentSettings effective(String hotelId) {
        return repository.findByHotelId(hotelId).orElseGet(() -> PaymentSettings.defaults(hotelId));
    }

    public PaymentSettingsResponse get(TenantContext tenant) {
        tenant.requireAnyPermission(Permission.TAKE_PAYMENT, Permission.MANAGE_HOTEL);
        return PaymentSettingsResponse.from(effective(tenant.hotelId()));
    }

    public PaymentSettingsResponse update(TenantContext tenant, UpdatePaymentSettingsRequest request) {
        tenant.require(Permission.MANAGE_HOTEL);

        EnumSet<PaymentMethod> methods = EnumSet.copyOf(request.enabledMethods());
        String upiId = clean(request.upiId());
        String payeeName = clean(request.payeeName());
        if (upiId != null && !UPI_ID.matcher(upiId).matches()) {
            throw validation("That is not a valid UPI id. It looks like name@bank");
        }
        if (methods.contains(PaymentMethod.UPI) && (upiId == null || payeeName == null)) {
            throw validation("To accept UPI, give a UPI id and the name it is registered under");
        }
        if (methods.contains(PaymentMethod.ONLINE) && !providers.configured(tenant.hotelId())) {
            throw validation("Connect the payment provider before turning on online payments");
        }

        List<PaymentMethod> stored = List.copyOf(methods);
        for (int attempt = 0; ; attempt++) {
            try {
                PaymentSettings saved = mongo.findAndModify(
                        Query.query(Criteria.where("hotelId").is(tenant.hotelId())),
                        new Update()
                                .set("enabledMethods", stored)
                                .set("upiId", upiId)
                                .set("payeeName", payeeName)
                                .set("partialPaymentsAllowed", request.partialPaymentsAllowed())
                                .set("updatedByUserId", tenant.userId())
                                .set("updatedAt", clock.instant()),
                        FindAndModifyOptions.options().upsert(true).returnNew(true), PaymentSettings.class);
                return PaymentSettingsResponse.from(saved);
            } catch (DuplicateKeyException firstInsertCollided) {
                if (attempt >= 1) {
                    throw firstInsertCollided; // the second try takes the update path, so this is not expected
                }
            }
        }
    }

    private static String clean(String text) {
        if (text == null) {
            return null;
        }
        String stripped = text.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static DomainException validation(String message) {
        return new DomainException(ErrorCode.VALIDATION_FAILED, message);
    }
}