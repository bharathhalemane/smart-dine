package com.example.qrorder.identity.model;

import java.time.Instant;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("users")
public class User {

    @Id
    private String id;

    @Indexed(unique = true)
    private String email; // always stored lowercase

    private String passwordHash;
    private String name;
    private boolean emailVerified;
    private UserStatus status = UserStatus.ACTIVE;
    private PlatformRole platformRole; // null for normal users
    private int failedLoginCount;
    private Instant lockedUntil;

    @CreatedDate
    private Instant createdAt;

    @Version
    private Long version;

    protected User() {}

    public User(String email, String passwordHash, String name) {
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.name = name;
        this.createdAt = Instant.now();
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }

    public String getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getName() { return name; }
    public boolean isEmailVerified() { return emailVerified; }
    public UserStatus getStatus() { return status; }
    public PlatformRole getPlatformRole() { return platformRole; }
    public int getFailedLoginCount() { return failedLoginCount; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getCreatedAt() { return createdAt; }

    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }
    public void setStatus(UserStatus status) { this.status = status; }
    public void setFailedLoginCount(int failedLoginCount) { this.failedLoginCount = failedLoginCount; }
    public void setLockedUntil(Instant lockedUntil) { this.lockedUntil = lockedUntil; }
    public void setPlatformRole(PlatformRole platformRole) {this.platformRole=platformRole;}
}