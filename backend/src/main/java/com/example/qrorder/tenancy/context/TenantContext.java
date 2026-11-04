package com.example.qrorder.tenancy.context;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.tenancy.model.HotelRole;
import java.util.Set;

/**
 * The verified answer to "who is acting, in which hotel, with which roles".
 * Built only by TenantAccessService after checking the database; services take this instead of a raw hotel id.
 */
public record TenantContext(String hotelId, String userId, Set<HotelRole> roles) {

    public boolean hasAnyRole(HotelRole... allowed) {
        for (HotelRole role : allowed) {
            if (roles.contains(role)) {
                return true;
            }
        }
        return false;
    }

    public void requireAnyRole(HotelRole... allowed) {
        if (!hasAnyRole(allowed)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "You do not have permission to do this");
        }
    }
}