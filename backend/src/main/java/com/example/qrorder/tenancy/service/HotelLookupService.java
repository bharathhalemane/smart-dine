package com.example.qrorder.tenancy.service;

import com.example.qrorder.tenancy.dto.PublicHotelInfo;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelStatus;
import com.example.qrorder.tenancy.repository.HotelRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** For public flows (a customer scanning a QR) where there is no logged-in member. */
@Service
public class HotelLookupService {

    private final HotelRepository hotels;

    public HotelLookupService(HotelRepository hotels) {
        this.hotels = hotels;
    }

    /** Empty when the hotel is missing, suspended or deactivated. */
    public Optional<PublicHotelInfo> findActive(String hotelId) {
        return hotels.findById(hotelId)
                .filter(h -> h.getStatus() == HotelStatus.ACTIVE)
                .map(h -> new PublicHotelInfo(h.getId(), h.getName()));
    }

    public Optional<String> findCurrency(String hotelId){
        return hotels.findById(hotelId).map(Hotel::getCurrency);
    }
}