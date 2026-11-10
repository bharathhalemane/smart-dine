package com.example.qrorder.realtime;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import com.example.qrorder.tenancy.event.HotelStatusChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

/**
 * Subscriptions are checked when they are made (11.2). This re-checks them while connections stay open, and
 * closes any connection whose token has expired or whose subscriptions would no longer be allowed: staff removed
 * or changed, hotel inactive, table session ended. A temporary failure of the check never closes anything.
 */
@Component
public class ConnectionWatch {

    private static final Logger log = LoggerFactory.getLogger(ConnectionWatch.class);

    private static final class Tracked {
        final RealtimePrincipal principal;
        final Map<String, Object> attributes;
        final Map<String, String> subscriptions = new ConcurrentHashMap<>(); // subscription id -> destination

        Tracked(RealtimePrincipal principal, Map<String, Object> attributes) {
            this.principal = principal;
            this.attributes = attributes;
        }
    }

    private final SubscriptionAuthorizer authorizer;
    private final Clock clock;
    private final TaskScheduler scheduler;
    private final long recheckSeconds;
    private final Map<String, WebSocketSession> sockets = new ConcurrentHashMap<>();
    private final Map<String, Tracked> tracked = new ConcurrentHashMap<>();
    private volatile ScheduledFuture<?> task;

    public ConnectionWatch(SubscriptionAuthorizer authorizer, Clock clock,
                           @Qualifier("wsHeartbeatScheduler") TaskScheduler scheduler,
                           @Value("${app.realtime.recheck-seconds:60}") long recheckSeconds) {
        this.authorizer = authorizer;
        this.clock = clock;
        this.scheduler = scheduler;
        this.recheckSeconds = Math.max(1, recheckSeconds);
    }

    @PostConstruct
    void start() {
        task = scheduler.scheduleWithFixedDelay(this::recheck, Duration.ofSeconds(recheckSeconds));
    }

    @PreDestroy
    void stop() {
        if (task != null) {
            task.cancel(false);
        }
    }

    /** Remembers each open socket, so it can be closed later. Plugged in by WebSocketConfig. */
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sockets.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                sockets.remove(session.getId());
                tracked.remove(session.getId());
                super.afterConnectionClosed(session, status);
            }
        };
    }

    // ---------- what each connection is listening to ----------

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        java.security.Principal user = event.getUser() != null ? event.getUser() : accessor.getUser();
        if (!(user instanceof RealtimePrincipal principal) || accessor.getSessionId() == null
                || accessor.getDestination() == null || accessor.getSubscriptionId() == null) {
            return;
        }
        tracked.computeIfAbsent(accessor.getSessionId(), id -> new Tracked(principal, accessor.getSessionAttributes()))
                .subscriptions.put(accessor.getSubscriptionId(), accessor.getDestination());
    }

    @EventListener
    public void onUnsubscribe(SessionUnsubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Tracked entry = accessor.getSessionId() == null ? null : tracked.get(accessor.getSessionId());
        if (entry != null && accessor.getSubscriptionId() != null) {
            entry.subscriptions.remove(accessor.getSubscriptionId());
        }
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        tracked.remove(event.getSessionId());
    }

    /** A hotel was suspended, deactivated or reactivated: do not wait for the next scheduled re-check. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onHotelStatus(HotelStatusChanged event) {
        recheck();
    }

    // ---------- the re-check ----------

    void recheck() {
        Instant now = clock.instant();
        tracked.forEach((sessionId, entry) -> {
            try {
                if (entry.principal.isExpired(now) || !stillAllowed(entry)) {
                    close(sessionId);
                }
            } catch (RuntimeException e) {
                log.warn("Could not re-check realtime connection {}", sessionId, e); // keep it; try again next time
            }
        });
    }

    private boolean stillAllowed(Tracked entry) {
        for (String destination : entry.subscriptions.values()) {
            try {
                authorizer.authorize(entry.principal, destination, entry.attributes);
            } catch (MessageDeliveryException refused) {
                return false;
            }
        }
        return true;
    }

    private void close(String sessionId) {
        tracked.remove(sessionId);
        WebSocketSession socket = sockets.get(sessionId);
        if (socket != null && socket.isOpen()) {
            try {
                socket.close(CloseStatus.POLICY_VIOLATION.withReason("Access changed. Reconnect"));
            } catch (IOException e) {
                log.debug("Connection {} was already closing", sessionId);
            }
        }
    }
}