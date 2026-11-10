package com.example.qrorder.realtime;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.security.BearerJwtConverter;
import com.example.qrorder.dining.service.CustomerContext;
import com.example.qrorder.dining.service.CustomerSessionService;
import java.time.Clock;
import java.util.Map;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Every frame a client sends passes through here. CONNECT proves identity; every later frame must come from
 * a connection that did. Clients may only send to /app/..., and may only subscribe where the
 * SubscriptionAuthorizer says. Anything else is refused with an ERROR frame and the connection is closed.
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

    static final String APP_PREFIX = "/app/";

    private final JwtDecoder decoder;
    private final BearerJwtConverter staffConverter;
    private final CustomerSessionService customers;
    private final SubscriptionAuthorizer subscriptions;
    private final Clock clock;

    public StompAuthInterceptor(JwtDecoder decoder, BearerJwtConverter staffConverter,
                                CustomerSessionService customers, SubscriptionAuthorizer subscriptions,
                                Clock clock) {
        this.decoder = decoder;
        this.staffConverter = staffConverter;
        this.customers = customers;
        this.subscriptions = subscriptions;
        this.clock = clock;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message; // heartbeats and internal events
        }
        StompCommand command = accessor.getCommand();
        switch (command) {
            case CONNECT, STOMP -> accessor.setUser(authenticate(accessor));
            case SEND -> {
                requireLive(accessor);
                requireAppDestination(accessor);
            }
            case SUBSCRIBE -> {
                RealtimePrincipal principal = requireLive(accessor);
                subscriptions.authorize(principal, accessor.getDestination(), accessor.getSessionAttributes());
            }
            case UNSUBSCRIBE, ACK, NACK, BEGIN, COMMIT, ABORT -> requireLive(accessor);
            default -> { } // DISCONNECT and friends need nothing
        }
        return message;
    }

    // ---------- connect ----------

    private RealtimePrincipal authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header != null && !header.isBlank()) {
            return staff(header);
        }
        Map<String, Object> attributes = accessor.getSessionAttributes();
        Object cookie = attributes == null ? null : attributes.get(CustomerCookieHandshake.ATTRIBUTE);
        if (cookie instanceof String token && !token.isBlank()) {
            return customer(token);
        }
        throw denied("Sign in or scan the QR code on your table first");
    }

    private RealtimePrincipal staff(String header) {
        if (!header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw denied("Send the access token as: Bearer <token>");
        }
        try {
            Jwt jwt = decoder.decode(header.substring(7).trim());
            AbstractAuthenticationToken auth = staffConverter.convert(jwt); // also checks the user is still active
            boolean admin = auth.getAuthorities().stream().anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
            return RealtimePrincipal.staff(jwt.getSubject(), admin, jwt.getExpiresAt());
        } catch (JwtException | AuthenticationException e) {
            throw denied("Your sign-in is not valid. Sign in again");
        }
    }

    private RealtimePrincipal customer(String token) {
        try {
            CustomerContext context = customers.resolve(token);
            return RealtimePrincipal.customer(context.customerSessionId(), context.hotelId(),
                    context.tableNumber(), context.diningSessionId());
        } catch (DomainException e) {
            throw denied("Scan the QR code on your table to continue");
        }
    }

    // ---------- every later frame ----------

    private RealtimePrincipal requireLive(StompHeaderAccessor accessor) {
        if (!(accessor.getUser() instanceof RealtimePrincipal principal)) {
            throw denied("Not connected");
        }
        if (principal.isExpired(clock.instant())) {
            throw denied("Your sign-in has expired. Reconnect with a fresh token");
        }
        return principal;
    }

    private static void requireAppDestination(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(APP_PREFIX) || destination.contains("..")) {
            throw denied("Clients may only send to application destinations");
        }
    }

    private static MessageDeliveryException denied(String message) {
        return new MessageDeliveryException(message);
    }
}