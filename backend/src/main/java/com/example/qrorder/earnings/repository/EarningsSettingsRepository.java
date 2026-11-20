package com.example.qrorder.earnings.repository;

import com.example.qrorder.earnings.model.EarningsSettings;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EarningsSettingsRepository extends MongoRepository<EarningsSettings, String>{
    Optional<EarningsSettings> findByHotelId(String hotelId);
    boolean existsByHotelId(String hotelId);
}
