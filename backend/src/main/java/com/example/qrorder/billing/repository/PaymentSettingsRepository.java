package com.example.qrorder.billing.repository;

import com.example.qrorder.billing.model.PaymentSettings;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PaymentSettingsRepository extends MongoRepository<PaymentSettings, String> {

    Optional<PaymentSettings> findByHotelId(String hotelId);
}