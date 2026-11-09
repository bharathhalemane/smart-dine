package com.example.qrorder.billing.service;

import com.example.qrorder.billing.dto.ProviderConfigRequest;
import com.example.qrorder.billing.dto.ProviderConfigResponse;
import com.example.qrorder.billing.model.HotelPaymentProvider;
import com.example.qrorder.billing.provider.ProviderCredentials;
import com.example.qrorder.billing.provider.SecretBox;
import com.example.qrorder.billing.repository.HotelPaymentProviderRepository;
import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import java.time.Clock;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class ProviderConfigService {

    static final String RAZORPAY = "razorpay";
    static final String WEBHOOK_PREFIX = "/api/v1/webhooks/razorpay/";

    private final HotelPaymentProviderRepository repository;
    private final MongoTemplate mongo;
    private final SecretBox secrets;
    private final Clock clock;

    public ProviderConfigService(HotelPaymentProviderRepository repository, MongoTemplate mongo, SecretBox secrets,
                                 Clock clock) {
        this.repository = repository;
        this.mongo = mongo;
        this.secrets = secrets;
        this.clock = clock;
    }

    public boolean configured(String hotelId) {
        return repository.findByHotelId(hotelId).isPresent();
    }

    public Optional<HotelPaymentProvider> find(String hotelId) {
        return repository.findByHotelId(hotelId);
    }

    public Optional<HotelPaymentProvider> findByHookKey(String hookKey) {
        return repository.findByHookKey(hookKey);
    }

    public ProviderCredentials credentials(HotelPaymentProvider config) {
        return new ProviderCredentials(config.getKeyId(), secrets.decrypt(config.getKeySecretEnc()),
                secrets.decrypt(config.getWebhookSecretEnc()));
    }

    public ProviderConfigResponse get(TenantContext tenant) {
        tenant.require(Permission.MANAGE_HOTEL);
        return repository.findByHotelId(tenant.hotelId()).map(ProviderConfigService::view)
                .orElseGet(ProviderConfigResponse::none);
    }

    public ProviderConfigResponse update(TenantContext tenant, ProviderConfigRequest request) {
        tenant.require(Permission.MANAGE_HOTEL);
        Query query = Query.query(Criteria.where("hotelId").is(tenant.hotelId()));
        Update update = new Update()
                .set("provider", RAZORPAY)
                .set("keyId", request.keyId().strip())
                .set("keySecretEnc", secrets.encrypt(request.keySecret().strip()))
                .set("webhookSecretEnc", secrets.encrypt(request.webhookSecret().strip()))
                .set("updatedByUserId", tenant.userId())
                .set("updatedAt", clock.instant())
                .setOnInsert("hookKey", Tokens.newToken()); // the webhook address never changes once issued

        for (int attempt = 0; ; attempt++) {
            try {
                HotelPaymentProvider saved = mongo.findAndModify(query, update,
                        FindAndModifyOptions.options().upsert(true).returnNew(true), HotelPaymentProvider.class);
                return view(saved);
            } catch (DuplicateKeyException firstInsertCollided) {
                if (attempt >= 1) {
                    throw firstInsertCollided;
                }
            }
        }
    }

    private static ProviderConfigResponse view(HotelPaymentProvider config) {
        return new ProviderConfigResponse(true, config.getProvider(), config.getKeyId(),
                WEBHOOK_PREFIX + config.getHookKey());
    }
}