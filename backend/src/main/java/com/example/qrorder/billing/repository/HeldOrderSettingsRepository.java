package com.example.qrorder.billing.repository;

import com.example.qrorder.billing.model.HeldOrderSettings;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface HeldOrderSettingsRepository extends MongoRepository<HeldOrderSettings, String> {

    Optional<HeldOrderSettings> findByHotelId(String hotelId);
}