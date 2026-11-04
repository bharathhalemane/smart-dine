package com.example.qrorder.tenancy.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelMembership;
import com.example.qrorder.tenancy.model.HotelStatus;
import com.example.qrorder.tenancy.model.MembershipStatus;
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class TenantAccessService {

    private final HotelMembershipRepository memberships;
    private final HotelRepository hotels;

    public TenantAccessService(HotelMembershipRepository memberships, HotelRepository hotels) {
        this.memberships = memberships;
        this.hotels = hotels;
    }

    /**
     * The one gate for hotel-scoped requests. Not a member (or membership removed) looks exactly like
     * "no such hotel" so hotel ids cannot be probed.
     */
    public TenantContext resolve(String hotelId, String userId) {
        HotelMembership membership = memberships.findByHotelIdAndUserId(hotelId, userId)
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .orElseThrow(() -> DomainException.notFound("Hotel"));

        Hotel hotel = hotels.findById(hotelId).orElseThrow(() -> DomainException.notFound("Hotel"));
        if (hotel.getStatus() != HotelStatus.ACTIVE) {
            throw new DomainException(ErrorCode.FORBIDDEN, "This hotel is not active");
        }
        return new TenantContext(hotelId, userId, Set.copyOf(membership.getRoles()));
    }
}