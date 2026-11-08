package com.example.qrorder.billing.repository;

import com.example.qrorder.billing.model.Bill;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface BillRepository extends MongoRepository<Bill, String> {

    Optional<Bill> findByDiningSessionId(String diningSessionId);
}