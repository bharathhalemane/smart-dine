package com.example.qrorder.billing.repository;

import com.example.qrorder.billing.model.SettlementSettings;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface SettlementSettingsRepository extends MongoRepository<SettlementSettings, String> {

    Optional<SettlementSettings> findByHotelId(String hotelId);
}