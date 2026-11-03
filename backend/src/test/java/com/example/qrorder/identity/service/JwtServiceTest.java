package com.example.qrorder.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.util.ReflectionTestUtils;
import com.example.qrorder.identity.config.JwtProperties;
import com.example.qrorder.identity.model.PlatformRole;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.config.JwtConfig;
class JwtServiceTest{
    private static final String SECRET = "unit-test-secret-unit-test-secret-1234";

    private final JwtProperties props = new JwtProperties(SECRET, "qr-order", Duration.ofMinutes(15));
    private final JwtConfig config = new JwtConfig();
    private final SecretKey key = config.jwtSecretKey(props);
    private final JwtService service = new JwtService(config.jwtEncoder(key), props, Clock.systemUTC());
    private final JwtDecoder decoder = config.jwtDecoder(key);

    private static User user(String id){
        User u = new User("a@example.com", "hash", "A");
        ReflectionTestUtils.setField(u,"id",id);
        return u;
    }

    @Test
    void tokenCarriesSubjectIssuerAndFifteenMinuteLifetime(){
        Jwt jwt = decoder.decode(service.issueAccessToken(user("u1")));

        assertThat(jwt.getSubject()).isEqualTo("u1");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("qr-order");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getClaimAsString("platformRole")).isNull();
    }

    @Test
    void platformAdminTokenCarriesTheRoleClaim(){
        User admin = user("admin1");
        admin.setPlatformRole(PlatformRole.PLATFORM_ADMIN);

        Jwt jwt = decoder.decode(service.issueAccessToken(admin));

        assertThat(jwt.getClaimAsString("platformRole")).isEqualTo("PLATFORM_ADMIN");
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected(){
        JwtProperties otherProps = new JwtProperties("a-completely-different-secret-0123456789","qr-order",Duration.ofMinutes(15));
        JwtService attacker = new JwtService(config.jwtEncoder(config.jwtSecretKey(otherProps)),otherProps,
                Clock.systemUTC());
        String forged = attacker.issueAccessToken(user("u1"));

        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void shortSecretIsRefusedAtString(){
        JwtProperties weak =new JwtProperties("short","qr-order",Duration.ofMinutes(15));
        assertThatThrownBy(() -> config.jwtSecretKey(weak)).isInstanceOf(IllegalStateException.class);
    }
}