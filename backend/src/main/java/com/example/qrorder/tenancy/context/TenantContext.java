package com.example.qrorder.tenancy.context;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.model.Permission;
import java.util.Set;

/**
 * The verified answer to "who is acting, in which hotel, with which roles".
 * Built only by TenantAccessService after checking the database; services take this instead of a raw hotel id.
 */
public record TenantContext(String hotelId, String userId, Set<HotelRole> roles,Set<Permission> permissions) {

    public TenantContext(String hotelId,String userId, Set<HotelRole> roles){
        this(hotelId, userId, roles, Set.of());
    }

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

    public boolean can(Permission permission){
        return permissions.contains(permission);
    }

    public void require(Permission permission){
        if(!can(permission)){
            throw new DomainException(ErrorCode.FORBIDDEN, "You do not have permission to do this");
        }
    }

    public void requireAnyPermission(Permission... allowed) {
        for (Permission permission : allowed) {
            if (permissions.contains(permission)) {
                return;
            }
        }
        throw new DomainException(ErrorCode.FORBIDDEN, "You do not have permission to do this");
    }
}