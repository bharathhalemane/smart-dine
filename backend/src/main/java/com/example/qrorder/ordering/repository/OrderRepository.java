package com.example.qrorder.ordering.repository;

import com.example.qrorder.ordering.model.Order;
import java.util.Optional;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OrderRepository extends MongoRepository<Order, String> {
    Optional<Order> findByCustomerSessionIdAndIdempotencyKey(String customerSessionId, String idempotencyKey);

    Optional<Order> findByIdAndCustomerSessionId(String id, String customerSessionId);

    List<Order> findFirst50ByCustomerSessionIdOrderByCreatedAtDescIdDesc(String customerSessionId);

}