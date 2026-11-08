package com.example.qrorder.billing.repository;

import com.example.qrorder.billing.model.BillingSettings;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface BillingSettingsRepository extends MongoRepository<BillingSettings, String> {

    Optional<BillingSettings> findByHotelId(String hotelId);
}