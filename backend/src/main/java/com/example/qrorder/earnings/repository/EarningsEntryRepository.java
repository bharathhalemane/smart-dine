package com.example.qrorder.earnings.repository;

import com.example.qrorder.earnings.model.EarningsEntry;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EarningsEntryRepository extends MongoRepository<EarningsEntry, String> {

    Optional<EarningsEntry> findByUniqueKey(String uniqueKey);
}