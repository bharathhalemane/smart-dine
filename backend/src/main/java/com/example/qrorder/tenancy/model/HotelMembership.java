package com.example.qrorder.tenancy.model;

import java.time.Instant;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("hotelMemberships")
@CompoundIndex(name = "hotel_user_unique", def = "{'hotelId': 1, 'userId': 1}", unique = true)
public class HotelMembership {

    @Id
    private String id;

    private String hotelId;

    @Indexed
    private String userId;

    private Set<HotelRole> roles;
    private MembershipStatus status = MembershipStatus.ACTIVE;
    private String invitedBy;
    private Instant createdAt;

    protected HotelMembership() {}

    public HotelMembership(String hotelId, String userId, Set<HotelRole> roles, String invitedBy, Instant createdAt) {
        this.hotelId = hotelId;
        this.userId = userId;
        this.roles = Set.copyOf(roles);
        this.invitedBy = invitedBy;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getHotelId() { return hotelId; }
    public String getUserId() { return userId; }
    public Set<HotelRole> getRoles() { return roles; }
    public MembershipStatus getStatus() { return status; }
    public String getInvitedBy() { return invitedBy; }
    public Instant getCreatedAt() { return createdAt; }

    public void setStatus(MembershipStatus status) { this.status = status; }
}