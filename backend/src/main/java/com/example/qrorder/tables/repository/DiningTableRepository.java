package com.example.qrorder.tables.repository;

import com.example.qrorder.tables.model.DiningTable;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Every query carries the hotel id. Do not use the inherited findById/deleteById from services:
 * they are not tenant-scoped.
 */
public interface DiningTableRepository extends MongoRepository<DiningTable, String> {

    Optional<DiningTable> findByIdAndHotelId(String id, String hotelId);

    List<DiningTable> findByHotelId(String hotelId);

    List<DiningTable> findByHotelIdAndActiveTrue(String hotelId);

    boolean existsByHotelIdAndNumber(String hotelId, String number);
}