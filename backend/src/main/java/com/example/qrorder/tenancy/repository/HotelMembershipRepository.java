package com.example.qrorder.tenancy.repository;

import com.example.qrorder.tenancy.model.HotelMembership;
import com.example.qrorder.tenancy.model.MembershipStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface HotelMembershipRepository extends MongoRepository<HotelMembership, String> {

    Optional<HotelMembership> findByHotelIdAndUserId(String hotelId, String userId);

    List<HotelMembership> findByUserIdAndStatus(String userId, MembershipStatus status);
}