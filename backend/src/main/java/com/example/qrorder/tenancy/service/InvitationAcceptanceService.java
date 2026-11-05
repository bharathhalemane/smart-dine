package com.example.qrorder.tenancy.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.identity.service.AccountService;
import com.example.qrorder.tenancy.dto.AcceptNewAccountRequest;
import com.example.qrorder.tenancy.dto.InvitationAcceptedResponse;
import com.example.qrorder.tenancy.dto.InvitationPreviewResponse;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelMembership;
import com.example.qrorder.tenancy.model.HotelStatus;
import com.example.qrorder.tenancy.model.InvitationStatus;
import com.example.qrorder.tenancy.model.MembershipStatus;
import com.example.qrorder.tenancy.model.StaffInvitation;
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
import com.example.qrorder.tenancy.repository.StaffInvitationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvitationAcceptanceService {

    private final MongoTemplate mongo;
    private final StaffInvitationRepository invitations;
    private final HotelMembershipRepository memberships;
    private final HotelRepository hotels;
    private final AccountService accounts;
    private final Clock clock;

    public InvitationAcceptanceService(MongoTemplate mongo, StaffInvitationRepository invitations,
                                       HotelMembershipRepository memberships, HotelRepository hotels,
                                       AccountService accounts, Clock clock) {
        this.mongo = mongo;
        this.invitations = invitations;
        this.memberships = memberships;
        this.hotels = hotels;
        this.accounts = accounts;
        this.clock = clock;
    }

    /** Read-only: lets the accept page decide between "sign in" and "create your account". */
    public InvitationPreviewResponse preview(String token) {
        StaffInvitation invitation = invitations.findByTokenHash(Tokens.sha256(token))
                .filter(i -> i.getStatus() == InvitationStatus.PENDING)
                .filter(i -> i.getExpiresAt().isAfter(clock.instant()))
                .orElseThrow(InvitationAcceptanceService::invalid);
        Hotel hotel = activeHotel(invitation.getHotelId());
        return new InvitationPreviewResponse(hotel.getName(), invitation.getEmail(),
                Set.copyOf(invitation.getRoles()),
                accounts.findUserIdByEmail(invitation.getEmail()).isPresent(),
                invitation.getExpiresAt());
    }

    /** An existing, signed-in user accepts. Their verified email must equal the invited email. */
    @Transactional
    public InvitationAcceptedResponse accept(String userId, String token) {
        String email = accounts.emailOf(userId).orElseThrow(InvitationAcceptanceService::invalid);
        if (!accounts.isEmailVerified(userId)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Verify your email address before accepting an invitation");
        }
        // The email is part of the query, so a wrong account does not burn the invitation.
        StaffInvitation invitation = consume(token, email);
        return join(invitation, userId);
    }

    /** A new person creates an account. Owning the emailed token proves they own the address. */
    @Transactional
    public InvitationAcceptedResponse acceptWithNewAccount(AcceptNewAccountRequest request) {
        StaffInvitation invitation = consume(request.token(), null);
        // If this throws (account exists, weak password), the transaction rolls the consume back.
        String userId = accounts.createVerifiedAccount(invitation.getEmail(), request.name(), request.password());
        return join(invitation, userId);
    }

    /** Atomic single use: of two simultaneous requests with the same token, only one gets the document. */
    private StaffInvitation consume(String token, String requiredEmail) {
        Criteria criteria = Criteria.where("tokenHash").is(Tokens.sha256(token))
                .and("status").is(InvitationStatus.PENDING)
                .and("expiresAt").gt(clock.instant());
        if (requiredEmail != null) {
            criteria = criteria.and("email").is(requiredEmail);
        }
        StaffInvitation consumed = mongo.findAndModify(
                Query.query(criteria),
                new Update().set("status", InvitationStatus.ACCEPTED),
                FindAndModifyOptions.options().returnNew(true),
                StaffInvitation.class);
        if (consumed == null) {
            throw invalid();
        }
        return consumed;
    }

    private InvitationAcceptedResponse join(StaffInvitation invitation, String userId) {
        Hotel hotel = activeHotel(invitation.getHotelId());
        Instant now = clock.instant();

        HotelMembership existing = memberships.findByHotelIdAndUserId(hotel.getId(), userId).orElse(null);
        if (existing == null) {
            memberships.save(new HotelMembership(hotel.getId(), userId, invitation.getRoles(),
                    invitation.getInvitedBy(), now));
        } else if (existing.getStatus() == MembershipStatus.ACTIVE) {
            throw DomainException.conflict("You are already a member of this hotel");
        } else {
            existing.setRoles(invitation.getRoles()); // previously removed: rejoin with the invited roles
            existing.setStatus(MembershipStatus.ACTIVE);
            memberships.save(existing);
        }
        return new InvitationAcceptedResponse(hotel.getId(), hotel.getName(), Set.copyOf(invitation.getRoles()));
    }

    private Hotel activeHotel(String hotelId) {
        return hotels.findById(hotelId)
                .filter(h -> h.getStatus() == HotelStatus.ACTIVE)
                .orElseThrow(InvitationAcceptanceService::invalid);
    }

    /** Same answer for unknown, expired, used, wrong-account and inactive-hotel cases. */
    private static DomainException invalid() {
        return DomainException.notFound("Invitation");
    }
}