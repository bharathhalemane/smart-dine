package com.example.qrorder.identity.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("emailVerificationTokens")
public class EmailVerificationToken {

    @Id
    private String id;

    @Indexed(unique = true)
    private String tokenHash; // SHA-256 of the token in the email link

    @Indexed
    private String userId;

    @Indexed(expireAfter = "0s") // MongoDB removes the document once it expires
    private Instant expiresAt;

    private Instant createdAt;

    protected EmailVerificationToken() {}

    public EmailVerificationToken(String tokenHash, String userId, Instant expiresAt, Instant createdAt) {
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getTokenHash() { return tokenHash; }
    public String getUserId() { return userId; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
}