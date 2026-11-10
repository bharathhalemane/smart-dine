package com.example.qrorder.realtime;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** Under /api/v1 so the guest's cookie (scoped to /api/v1) is sent with the handshake. */
    public static final String ENDPOINT = "/api/v1/ws";

    private final StompAuthInterceptor authInterceptor;
    private final CustomerCookieHandshake cookieHandshake;
    private final TaskScheduler heartbeats;
    private final String[] allowedOrigins;

    public WebSocketConfig(StompAuthInterceptor authInterceptor, CustomerCookieHandshake cookieHandshake,
                           @Qualifier("wsHeartbeatScheduler") TaskScheduler heartbeats,
                           @Value("${app.cors.allowed-origins}") String origins) {
        this.authInterceptor = authInterceptor;
        this.cookieHandshake = cookieHandshake;
        this.heartbeats = heartbeats;
        this.allowedOrigins = Arrays.stream(origins.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    @Bean(name = "wsHeartbeatScheduler")
    static ThreadPoolTaskScheduler wsHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        return scheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(ENDPOINT)
                .setAllowedOriginPatterns(allowedOrigins) // the same allowlist as CORS, never "*"
                .addInterceptors(cookieHandshake);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[] {10_000, 10_000})
                .setTaskScheduler(heartbeats);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(16 * 1024)
                .setSendBufferSizeLimit(512 * 1024)
                .setSendTimeLimit(20_000)
                .setTimeToFirstMessage(30_000);
    }
}