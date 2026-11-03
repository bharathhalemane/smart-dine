package com.example.qrorder.identity.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.config.JwtProperties;

@Service
public class JwtService{

    private final JwtEncoder encoder;
    private final JwtProperties props;
    private final Clock clock;

    public JwtService(JwtEncoder encoder, JwtProperties props, Clock clock){
        this.encoder = encoder;
        this.props = props;
        this.clock = clock;
    }

    public String issueAccessToken(User user){
        Instant now = clock.instant();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(user.getId())
                .issuedAt(now)
                .expiresAt(now.plus(props.accessTokenTtl()))
                .id(UUID.randomUUID().toString());
        if(user.getPlatformRole() != null) {
            claims.claim("platformRole", user.getPlatformRole().name());
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}