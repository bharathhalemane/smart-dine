package com.example.qrorder.tenancy.service;

import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.model.MembershipStatus;
import com.example.qrorder.tenancy.model.Permission;
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Public API of the tenancy module for other modules: who works at a hotel and what they may do. */
@Service
public class StaffDirectory {

    public record StaffRef(String userId, Set<HotelRole> roles, Set<Permission> permissions) {}

    private final HotelMembershipRepository memberships;
    private final HotelRepository hotels;

    public StaffDirectory(HotelMembershipRepository memberships, HotelRepository hotels) {
        this.memberships = memberships;
        this.hotels = hotels;
    }

    /** The person's current roles and permissions here, or empty if they are not an active member. */
    public Optional<StaffRef> findActive(String hotelId, String userId) {
        Optional<Hotel> hotel = hotels.findById(hotelId);
        if (hotel.isEmpty()) {
            return Optional.empty();
        }
        return memberships.findByHotelIdAndUserId(hotelId, userId)
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .map(m -> new StaffRef(m.getUserId(), m.getRoles(),
                        PermissionResolver.resolve(m.getRoles(), hotel.get())));
    }

    /** Ids of the active members of this hotel who currently hold the permission. */
    public Set<String> userIdsWith(String hotelId, Permission permission) {
        Hotel hotel = hotels.findById(hotelId).orElse(null);
        if (hotel == null) {
            return Set.of();
        }
        return memberships.findByHotelIdAndStatus(hotelId, MembershipStatus.ACTIVE).stream()
                .filter(m -> PermissionResolver.resolve(m.getRoles(), hotel).contains(permission))
                .map(m -> m.getUserId())
                .collect(Collectors.toSet());
    }
}