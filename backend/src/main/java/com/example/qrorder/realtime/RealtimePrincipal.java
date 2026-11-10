package com.example.qrorder.realtime;

import java.security.Principal;
import java.time.Instant;

/**
 * Who is on a WebSocket connection. A staff member proves it with an access token (which expires), a guest
 * with the table cookie. Which hotel a staff member may listen to is decided per subscription in 11.2.
 */
public record RealtimePrincipal(Kind kind, String id, String hotelId, String tableNumber, String diningSessionId,
                                boolean platformAdmin, Instant expiresAt) implements Principal {

    public enum Kind { STAFF, CUSTOMER }

    public static RealtimePrincipal staff(String userId, boolean platformAdmin, Instant tokenExpiresAt) {
        return new RealtimePrincipal(Kind.STAFF, userId, null, null, null, platformAdmin, tokenExpiresAt);
    }

    public static RealtimePrincipal customer(String customerSessionId, String hotelId, String tableNumber,
                                             String diningSessionId) {
        return new RealtimePrincipal(Kind.CUSTOMER, customerSessionId, hotelId, tableNumber, diningSessionId,
                false, null);
    }

    /** Unique across both kinds, and what private messages (/user/queue/...) are addressed to. */
    @Override
    public String getName() {
        return kind == Kind.STAFF ? id : "customer:" + id;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}