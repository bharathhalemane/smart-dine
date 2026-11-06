package com.example.qrorder.tables.repository;

import com.example.qrorder.tables.model.QrCredential;
import com.example.qrorder.tables.model.QrStatus;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface QrCredentialRepository extends MongoRepository<QrCredential, String> {

    Optional<QrCredential> findByHotelIdAndTableIdAndStatus(String hotelId, String tableId, QrStatus status);
}