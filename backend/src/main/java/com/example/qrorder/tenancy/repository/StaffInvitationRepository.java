package com.example.qrorder.tenancy.repository;

import com.example.qrorder.tenancy.model.InvitationStatus;
import com.example.qrorder.tenancy.model.StaffInvitation;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface StaffInvitationRepository extends MongoRepository<StaffInvitation, String> {

    List<StaffInvitation> findByHotelIdAndEmailAndStatus(String hotelId, String email, InvitationStatus status);

    List<StaffInvitation> findByHotelIdAndStatusOrderByCreatedAtDesc(String hotelId, InvitationStatus status);
}