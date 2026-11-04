package com.example.qrorder.common.security;

import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Turns a verified JWT into the request's authentication. The token's signature and expiry are already checked
 * by Spring; here we also confirm the user still exists and is active, so disabling an account is immediate.
 * The authentication's name is the user id.
 */
@Component
public class BearerJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final ActiveUserCheck activeUsers;

    public BearerJwtConverter(ActiveUserCheck activeUsers) {
        this.activeUsers = activeUsers;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String userId = jwt.getSubject();
        if (userId == null || !activeUsers.isActive(userId)) {
            throw new BadCredentialsException("User is not active");
        }
        List<GrantedAuthority> authorities = "PLATFORM_ADMIN".equals(jwt.getClaimAsString("platformRole"))
                ? List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))
                : List.of();
        return new JwtAuthenticationToken(jwt, authorities, userId);
    }
}