package com.example.qrorder.tenancy.model;

import java.time.Instant;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Only the SHA-256 hash of the invite token is stored; the plain token exists only in the email. */
@Document("staffInvitations")
@CompoundIndex(name = "hotel_email_status", def = "{'hotelId': 1, 'email': 1, 'status': 1}")
public class StaffInvitation {

    @Id
    private String id;

    private String hotelId;
    private String email; // normalized: trimmed, lowercase
    private Set<HotelRole> roles;

    @Indexed(unique = true)
    private String tokenHash;

    private String invitedBy;
    private InvitationStatus status = InvitationStatus.PENDING;
    private Instant expiresAt;
    private Instant createdAt;

    protected StaffInvitation() {}

    public StaffInvitation(String hotelId, String email, Set<HotelRole> roles, String tokenHash,
                           String invitedBy, Instant expiresAt, Instant createdAt) {
        this.hotelId = hotelId;
        this.email = email;
        this.roles = Set.copyOf(roles);
        this.tokenHash = tokenHash;
        this.invitedBy = invitedBy;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getHotelId() { return hotelId; }
    public String getEmail() { return email; }
    public Set<HotelRole> getRoles() { return roles; }
    public String getTokenHash() { return tokenHash; }
    public String getInvitedBy() { return invitedBy; }
    public InvitationStatus getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void setStatus(InvitationStatus status) { this.status = status; }
}