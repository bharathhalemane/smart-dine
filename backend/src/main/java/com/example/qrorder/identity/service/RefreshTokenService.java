package com.example.qrorder.identity.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.identity.config.RefreshProperties;
import com.example.qrorder.identity.model.RefreshToken;
import com.example.qrorder.identity.repository.RefreshTokenRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class RefreshTokenService {

    public record Rotation(String userId, String refreshToken) {}

    private final RefreshTokenRepository tokens;
    private final MongoTemplate mongo;
    private final RefreshProperties props;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenRepository tokens, MongoTemplate mongo,
                               RefreshProperties props, Clock clock) {
        this.tokens = tokens;
        this.mongo = mongo;
        this.props = props;
        this.clock = clock;
    }

    /** Starts a new token family (one per login) and returns the plaintext token for the cookie. */
    public String createSession(String userId) {
        return issue(userId, UUID.randomUUID().toString());
    }

    /**
     * Atomically consumes the presented token and issues its successor.
     * The conditional update means two simultaneous requests cannot both succeed with the same token.
     */
    public Rotation rotate(String presentedToken) {
        Instant now = clock.instant();
        String hash = Tokens.sha256(presentedToken);

        Query live = Query.query(Criteria.where("tokenHash").is(hash)
                .and("usedAt").isNull()
                .and("revokedAt").isNull()
                .and("expiresAt").gt(now));
        RefreshToken consumed = mongo.findAndModify(live, new Update().set("usedAt", now), RefreshToken.class);

        if (consumed == null) {
            // A token that was already used or revoked is being replayed: assume it leaked, kill the family.
            tokens.findByTokenHash(hash)
                    .filter(t -> t.getUsedAt() != null || t.getRevokedAt() != null)
                    .ifPresent(t -> revokeFamily(t.getFamilyId(), now));
            throw invalid();
        }
        return new Rotation(consumed.getUserId(), issue(consumed.getUserId(), consumed.getFamilyId()));
    }

    /** Logout: revokes every token in the presented token's family. Unknown tokens are ignored. */
    public void revokeFamilyOf(String presentedToken) {
        Instant now = clock.instant();
        tokens.findByTokenHash(Tokens.sha256(presentedToken))
                .ifPresent(t -> revokeFamily(t.getFamilyId(), now));
    }

    private String issue(String userId, String familyId) {
        String plain = Tokens.newToken();
        Instant now = clock.instant();
        tokens.save(new RefreshToken(Tokens.sha256(plain), userId, familyId, now.plus(props.ttl()), now));
        return plain;
    }

    private void revokeFamily(String familyId, Instant now) {
        mongo.updateMulti(
                Query.query(Criteria.where("familyId").is(familyId).and("revokedAt").isNull()),
                new Update().set("revokedAt", now),
                RefreshToken.class);
    }

    private static DomainException invalid() {
        return new DomainException(ErrorCode.UNAUTHENTICATED, "Invalid or expired session");
    }
}