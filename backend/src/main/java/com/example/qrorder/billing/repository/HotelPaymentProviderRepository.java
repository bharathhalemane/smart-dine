package com.example.qrorder.billing.repository;

import com.example.qrorder.billing.model.HotelPaymentProvider;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface HotelPaymentProviderRepository extends MongoRepository<HotelPaymentProvider, String> {

    Optional<HotelPaymentProvider> findByHotelId(String hotelId);

    Optional<HotelPaymentProvider> findByHookKey(String hookKey);
}