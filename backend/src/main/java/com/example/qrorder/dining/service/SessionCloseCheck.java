package com.example.qrorder.dining.service;

import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.tenancy.context.TenantContext;
import java.util.Optional;

public interface SessionCloseCheck {

    void check(TenantContext tenant, DiningSession session);

    /** True when this check's verdict depends on the session's orders, so the close must not race a new order. */
    default boolean guardsOrderCount(DiningSession session) {
        return false;
    }

    /** Same check, told whether the caller is forcing the clear. Checks that have no override ignore the flag. */
    default void check(TenantContext tenant, DiningSession session, boolean forced) {
        check(tenant, session);
    }

    /** What a forced clear would leave behind, for the audit trail. Empty when there is nothing to note. */
    default Optional<String> warning(DiningSession session) {
        return Optional.empty();
    }
}