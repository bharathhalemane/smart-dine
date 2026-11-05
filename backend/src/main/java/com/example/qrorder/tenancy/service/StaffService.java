package com.example.qrorder.tenancy.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.identity.service.AccountService;
import com.example.qrorder.identity.service.AccountSummary;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.dto.ChangeRolesRequest;
import com.example.qrorder.tenancy.dto.StaffMemberResponse;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelMembership;
import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.model.MembershipStatus;
import com.example.qrorder.tenancy.model.Permission;
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import java.time.Clock;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StaffService {

    private final HotelMembershipRepository memberships;
    private final AccountService accounts;
    private final MongoTemplate mongo;
    private final Clock clock;

    public StaffService(HotelMembershipRepository memberships, AccountService accounts,
                        MongoTemplate mongo, Clock clock) {
        this.memberships = memberships;
        this.accounts = accounts;
        this.mongo = mongo;
        this.clock = clock;
    }

    public List<StaffMemberResponse> list(TenantContext actor) {
        actor.require(Permission.MANAGE_STAFF);
        List<HotelMembership> active = memberships.findByHotelIdAndStatus(actor.hotelId(), MembershipStatus.ACTIVE);
        Map<String, AccountSummary> people = accounts.summaries(active.stream().map(HotelMembership::getUserId).toList());

        return active.stream()
                .filter(m -> people.containsKey(m.getUserId()))
                .map(m -> toResponse(m, people.get(m.getUserId())))
                .sorted(Comparator.comparing(StaffMemberResponse::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional
    public StaffMemberResponse changeRoles(TenantContext actor, String userId, ChangeRolesRequest request) {
        actor.require(Permission.MANAGE_STAFF);
        if (actor.userId().equals(userId)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "You cannot change your own roles");
        }
        HotelMembership target = activeTarget(actor, userId);
        requireMayManage(actor, target);

        Set<HotelRole> newRoles = EnumSet.copyOf(request.roles());
        boolean wasOwner = target.getRoles().contains(HotelRole.OWNER);
        if (newRoles.contains(HotelRole.OWNER) && !wasOwner) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "The owner role cannot be granted here");
        }
        if (newRoles.contains(HotelRole.MANAGER) && !actor.hasAnyRole(HotelRole.OWNER)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Only the owner can assign the manager role");
        }
        if (wasOwner && !newRoles.contains(HotelRole.OWNER)) {
            requireAnotherOwner(actor.hotelId(), userId);
        }

        target.setRoles(newRoles);
        memberships.save(target);
        return toResponse(target, accounts.summaries(List.of(userId)).get(userId));
    }

    @Transactional
    public void remove(TenantContext actor, String userId) {
        actor.require(Permission.MANAGE_STAFF);
        HotelMembership target = activeTarget(actor, userId);
        if (!actor.userId().equals(userId)) {
            requireMayManage(actor, target); // leaving the hotel yourself is always allowed
        }
        if (target.getRoles().contains(HotelRole.OWNER)) {
            requireAnotherOwner(actor.hotelId(), userId);
        }

        target.setStatus(MembershipStatus.REMOVED);
        memberships.save(target);
    }

    /** Scoped to the actor's hotel: a user id from another hotel is simply "not found". */
    private HotelMembership activeTarget(TenantContext actor, String userId) {
        return memberships.findByHotelIdAndUserId(actor.hotelId(), userId)
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .orElseThrow(() -> DomainException.notFound("Staff member"));
    }

    /** Managers manage ordinary staff only; owners and managers are the owner's to change. */
    private static void requireMayManage(TenantContext actor, HotelMembership target) {
        boolean privileged = target.getRoles().contains(HotelRole.OWNER) || target.getRoles().contains(HotelRole.MANAGER);
        if (privileged && !actor.hasAnyRole(HotelRole.OWNER)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Only the owner can change an owner or a manager");
        }
    }

    /**
     * Last-owner rule. The hotel document is written first so that two concurrent transactions
     * (two owners removing each other) conflict instead of both seeing "another owner exists".
     */
    private void requireAnotherOwner(String hotelId, String leavingUserId) {
        mongo.updateFirst(Query.query(Criteria.where("id").is(hotelId)),
                new Update().set("updatedAt", clock.instant()), Hotel.class);

        long others = memberships.findByHotelIdAndStatus(hotelId, MembershipStatus.ACTIVE).stream()
                .filter(m -> m.getRoles().contains(HotelRole.OWNER))
                .filter(m -> !m.getUserId().equals(leavingUserId))
                .count();
        if (others == 0) {
            throw DomainException.conflict("A hotel must keep at least one owner");
        }
    }

    private static StaffMemberResponse toResponse(HotelMembership m, AccountSummary person) {
        return new StaffMemberResponse(m.getUserId(), person.name(), person.email(),
                Set.copyOf(m.getRoles()), m.getCreatedAt());
    }
}