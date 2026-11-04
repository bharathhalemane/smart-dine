package com.example.qrorder.tenancy.repository;

import com.example.qrorder.tenancy.model.Hotel;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface HotelRepository extends MongoRepository<Hotel, String> {

    boolean existsBySlug(String slug);
}