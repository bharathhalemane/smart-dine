package com.example.qrorder.realtime;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.security.ActiveUserCheck;
import com.example.qrorder.dining.service.CustomerContext;
import com.example.qrorder.dining.service.CustomerSessionService;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import com.example.qrorder.tenancy.service.TenantAccessService;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.stereotype.Component;

/**
 * Decides, at the moment of each SUBSCRIBE, whether this connection may listen to that destination.
 * The broker treats subscriptions as patterns (a client asking for /topic/** would hear everything), so only
 * a few exact shapes with plain ids are allowed. Everything else, and every refusal, gets the same answer.
 */
@Component
public class SubscriptionAuthorizer {

    private static final String ID = "[A-Za-z0-9_-]{1,64}";
    private static final Pattern OWN_QUEUE = Pattern.compile("/user/queue/" + ID);
    private static final Pattern STAFF_TOPIC = Pattern.compile("/topic/hotels/(" + ID + ")/(kitchen|waiters|billing)");
    private static final Pattern TABLE_TOPIC = Pattern.compile("/topic/tables/(" + ID + ")");

    private final TenantAccessService tenantAccess;
    private final ActiveUserCheck activeUsers;
    private final CustomerSessionService customers;

    public SubscriptionAuthorizer(TenantAccessService tenantAccess, ActiveUserCheck activeUsers,
                                  CustomerSessionService customers) {
        this.tenantAccess = tenantAccess;
        this.activeUsers = activeUsers;
        this.customers = customers;
    }

    /** Returns normally if allowed; throws otherwise. */
    public void authorize(RealtimePrincipal principal, String destination, Map<String, Object> sessionAttributes) {
        if (destination == null) {
            throw refused();
        }
        if (OWN_QUEUE.matcher(destination).matches()) {
            return;
        }
        Matcher staff = STAFF_TOPIC.matcher(destination);
        if (staff.matches()) {
            authorizeStaff(principal, staff.group(1), staff.group(2));
            return;
        }
        Matcher table = TABLE_TOPIC.matcher(destination);
        if (table.matches()) {
            authorizeGuest(principal, table.group(1), sessionAttributes);
            return;
        }
        throw refused();
    }

    private void authorizeStaff(RealtimePrincipal principal, String hotelId, String channel) {
        if (principal.kind() != RealtimePrincipal.Kind.STAFF || !activeUsers.isActive(principal.id())) {
            throw refused();
        }
        TenantContext tenant;
        try {
            tenant = tenantAccess.resolve(hotelId, principal.id()); // member of an active hotel, permissions fresh
        } catch (DomainException e) {
            throw refused();
        }
        Permission needed = switch (channel) {
            case "kitchen" -> Permission.KITCHEN_OPERATE;
            case "waiters" -> Permission.DELIVER_ORDERS;
            default -> Permission.TAKE_PAYMENT; // billing
        };
        if (!tenant.can(needed)) {
            throw refused();
        }
    }

    private void authorizeGuest(RealtimePrincipal principal, String diningSessionId,
                                Map<String, Object> sessionAttributes) {
        Object cookie = sessionAttributes == null ? null : sessionAttributes.get(CustomerCookieHandshake.ATTRIBUTE);
        if (principal.kind() != RealtimePrincipal.Kind.CUSTOMER || !(cookie instanceof String token)) {
            throw refused();
        }
        try {
            CustomerContext context = customers.resolve(token); // fails once the table session has ended
            if (!diningSessionId.equals(context.diningSessionId())
                    || !principal.id().equals(context.customerSessionId())) {
                throw refused();
            }
        } catch (DomainException e) {
            throw refused();
        }
    }

    private static MessageDeliveryException refused() {
        return new MessageDeliveryException("You cannot subscribe to that destination");
    }
}