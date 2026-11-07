package com.example.qrorder.ordering.repository;

import com.example.qrorder.ordering.model.Order;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OrderRepository extends MongoRepository<Order, String> {
    Optional<Order> findByCustomerSessionIdAndIdempotencyKey(String customerSessionId, String idempotencyKey);
}