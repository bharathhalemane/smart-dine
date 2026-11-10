package com.example.qrorder.realtime;

import com.example.qrorder.dining.service.CustomerCookies;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Keeps the guest's table cookie for the life of the connection. The cookie is only read here; whether it is
 * valid is decided when the client sends CONNECT.
 */
@Component
public class CustomerCookieHandshake implements HandshakeInterceptor {

    public static final String ATTRIBUTE = "customerToken";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                                   Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servlet && servlet.getServletRequest().getCookies() != null) {
            for (Cookie cookie : servlet.getServletRequest().getCookies()) {
                if (CustomerCookies.NAME.equals(cookie.getName()) && cookie.getValue() != null) {
                    attributes.put(ATTRIBUTE, cookie.getValue());
                }
            }
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                               Exception exception) {
        // nothing to do
    }
}