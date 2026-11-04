package com.example.qrorder.identity.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("refreshTokens")
public class RefreshToken {

    @Id
    private String id;

    @Indexed(unique = true)
    private String tokenHash; // SHA-256 of the cookie value; the plaintext is never stored

    @Indexed
    private String userId;

    @Indexed
    private String familyId; // all tokens descending from one login share this

    @Indexed(expireAfter = "0s") // MongoDB deletes the document once expiresAt passes
    private Instant expiresAt;

    private Instant createdAt;
    private Instant usedAt;    // set when rotated; presenting it again means reuse
    private Instant revokedAt; // set on logout or family revocation

    protected RefreshToken() {}

    public RefreshToken(String tokenHash, String userId, String familyId, Instant expiresAt, Instant createdAt) {
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.familyId = familyId;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getTokenHash() { return tokenHash; }
    public String getUserId() { return userId; }
    public String getFamilyId() { return familyId; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUsedAt() { return usedAt; }
    public Instant getRevokedAt() { return revokedAt; }
}