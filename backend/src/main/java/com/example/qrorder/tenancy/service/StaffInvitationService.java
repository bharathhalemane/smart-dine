package com.example.qrorder.tenancy.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.common.mail.EmailSender;
import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.identity.service.AccountService;
import com.example.qrorder.tenancy.config.InvitationProperties;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.dto.InvitationResponse;
import com.example.qrorder.tenancy.dto.InviteStaffRequest;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelMembership;
import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.model.InvitationStatus;
import com.example.qrorder.tenancy.model.MembershipStatus;
import com.example.qrorder.tenancy.model.Permission;
import com.example.qrorder.tenancy.model.StaffInvitation;
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
import com.example.qrorder.tenancy.repository.StaffInvitationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class StaffInvitationService {

    private static final Logger log = LoggerFactory.getLogger(StaffInvitationService.class);

    private final StaffInvitationRepository invitations;
    private final HotelMembershipRepository memberships;
    private final HotelRepository hotels;
    private final AccountService accounts;
    private final EmailSender emailSender;
    private final InvitationProperties props;
    private final Clock clock;

    public StaffInvitationService(StaffInvitationRepository invitations, HotelMembershipRepository memberships,
                                  HotelRepository hotels, AccountService accounts, EmailSender emailSender,
                                  InvitationProperties props, Clock clock) {
        this.invitations = invitations;
        this.memberships = memberships;
        this.hotels = hotels;
        this.accounts = accounts;
        this.emailSender = emailSender;
        this.props = props;
        this.clock = clock;
    }

    public InvitationResponse invite(TenantContext tenant, InviteStaffRequest request) {
        tenant.require(Permission.MANAGE_STAFF);

        Set<HotelRole> roles = EnumSet.copyOf(request.roles());
        if (roles.contains(HotelRole.OWNER)) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "The owner role cannot be assigned by invitation");
        }
        if (roles.contains(HotelRole.MANAGER) && !tenant.hasAnyRole(HotelRole.OWNER)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Only the owner can invite a manager");
        }

        String email = request.email().trim().toLowerCase(Locale.ROOT);

        boolean alreadyMember = accounts.findUserIdByEmail(email)
                .flatMap(userId -> memberships.findByHotelIdAndUserId(tenant.hotelId(), userId))
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .isPresent();
        if (alreadyMember) {
            throw DomainException.conflict("This person is already a member of the hotel");
        }

        // One live invitation per hotel and email: a new invite replaces the old one.
        List<StaffInvitation> older = invitations.findByHotelIdAndEmailAndStatus(
                tenant.hotelId(), email, InvitationStatus.PENDING);
        older.forEach(i -> i.setStatus(InvitationStatus.REVOKED));
        invitations.saveAll(older);

        String plainToken = Tokens.newToken();
        Instant now = clock.instant();
        StaffInvitation saved = invitations.save(new StaffInvitation(
                tenant.hotelId(), email, roles, Tokens.sha256(plainToken),
                tenant.userId(), now.plus(props.ttl()), now));

        sendEmail(tenant, saved, plainToken);
        return InvitationResponse.from(saved);
    }

    public List<InvitationResponse> listPending(TenantContext tenant) {
        tenant.require(Permission.MANAGE_STAFF);
        Instant now = clock.instant();
        return invitations.findByHotelIdAndStatusOrderByCreatedAtDesc(tenant.hotelId(), InvitationStatus.PENDING)
                .stream()
                .filter(i -> i.getExpiresAt().isAfter(now))
                .map(InvitationResponse::from)
                .toList();
    }

    private void sendEmail(TenantContext tenant, StaffInvitation invitation, String plainToken) {
        String hotelName = hotels.findById(tenant.hotelId()).map(Hotel::getName).orElse("a restaurant");
        String inviter = accounts.displayName(tenant.userId());
        String link = props.linkBaseUrl() + "/accept-invitation?token=" + plainToken;
        String body = """
                Hi,

                %s invited you to join %s as %s.

                Accept the invitation here:
                %s

                This link expires in %d days. If you weren't expecting this, you can ignore this email.
                """.formatted(inviter, hotelName, invitation.getRoles(), link, props.ttl().toDays());
        try {
            emailSender.send(invitation.getEmail(), "You're invited to join " + hotelName, body);
        } catch (RuntimeException e) {
            // The invitation is stored; the manager can invite again. Do not fail the request over mail.
            log.warn("Could not send staff invitation email for invitation {}", invitation.getId(), e);
        }
    }
}