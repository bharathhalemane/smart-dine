package com.example.qrorder.dining.repository;

import com.example.qrorder.dining.model.DiningSession;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface DiningSessionRepository extends MongoRepository<DiningSession, String> {

    Optional<DiningSession> findByTableIdAndActiveTrue(String tableId);

    List<DiningSession> findByHotelIdAndActiveTrue(String hotelId);
}