package com.example.qrorder.billing.repository;

import com.example.qrorder.billing.model.Payment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PaymentRepository extends MongoRepository<Payment, String> {

    Optional<Payment> findByDiningSessionIdAndIdempotencyKey(String diningSessionId, String idempotencyKey);

    List<Payment> findByDiningSessionIdOrderByReceivedAtAscIdAsc(String diningSessionId);

    Optional<Payment> findByIdAndHotelId(String id, String hotelId);
}