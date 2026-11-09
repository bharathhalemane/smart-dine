package com.example.qrorder.billing.repository;

import com.example.qrorder.billing.model.OnlinePayment;
import com.example.qrorder.billing.model.OnlinePaymentStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OnlinePaymentRepository extends MongoRepository<OnlinePayment, String> {

    Optional<OnlinePayment> findByCustomerSessionIdAndIdempotencyKey(String customerSessionId, String key);

    Optional<OnlinePayment> findByProviderOrderId(String providerOrderId);

    Optional<OnlinePayment> findByIdAndDiningSessionId(String id, String diningSessionId);

    List<OnlinePayment> findByHotelIdAndStatusOrderByUpdatedAtAscIdAsc(String hotelId, OnlinePaymentStatus status);
}