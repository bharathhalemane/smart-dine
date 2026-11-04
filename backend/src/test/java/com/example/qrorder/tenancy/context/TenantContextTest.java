package com.example.qrorder.tenancy.context;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.tenancy.model.HotelRole;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TenantContextTest {

    private final TenantContext manager = new TenantContext("h1", "u1", Set.of(HotelRole.MANAGER));

    @Test
    void passesWhenOneOfTheAllowedRolesMatches() {
        assertThatCode(() -> manager.requireAnyRole(HotelRole.OWNER, HotelRole.MANAGER)).doesNotThrowAnyException();
    }

    @Test
    void rejectsWhenNoRoleMatches() {
        assertThatThrownBy(() -> manager.requireAnyRole(HotelRole.OWNER))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("permission");
    }
}