package com.example.qrorder.dining.repository;

import com.example.qrorder.dining.model.CustomerSession;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CustomerSessionRepository extends MongoRepository<CustomerSession, String> {

    Optional<CustomerSession> findByTokenHash(String tokenHash);
}