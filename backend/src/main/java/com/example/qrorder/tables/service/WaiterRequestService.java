package com.example.qrorder.tables.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.identity.service.AccountService;
import com.example.qrorder.identity.service.AccountSummary;
import com.example.qrorder.tables.dto.RaiseRequest;
import com.example.qrorder.tables.dto.TableResponse;
import com.example.qrorder.tables.dto.WaiterRequestResponse;
import com.example.qrorder.tables.model.RequestKind;
import com.example.qrorder.tables.model.RequestStatus;
import com.example.qrorder.tables.model.WaiterEventType;
import com.example.qrorder.tables.model.WaiterRequest;
import com.example.qrorder.tables.repository.WaiterRequestRepository;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class WaiterRequestService {

    public record Raised(WaiterRequestResponse request, boolean created) {}

    private final WaiterRequestRepository requests;
    private final MongoTemplate mongo;
    private final WaiterAssignmentService assignments;
    private final WaiterAuditService audit;
    private final AccountService accounts;
    private final Clock clock;

    public WaiterRequestService(WaiterRequestRepository requests, MongoTemplate mongo,
                                WaiterAssignmentService assignments, WaiterAuditService audit,
                                AccountService accounts, Clock clock) {
        this.requests = requests;
        this.mongo = mongo;
        this.assignments = assignments;
        this.audit = audit;
        this.accounts = accounts;
        this.clock = clock;
    }

    // ---------- raise ----------

    public Raised raise(TenantContext tenant, String tableId, RaiseRequest body) {
        tenant.require(Permission.DELIVER_ORDERS);
        TableResponse table = assignments.activeTable(tenant, tableId);
        String me = tenant.userId();
        String note = clean(body.note());
        String target = clean(body.toUserId());

        if (body.kind() == RequestKind.HELP && target != null) {
            throw validation("A person can only be named in a hand-over request");
        }
        if (body.kind() == RequestKind.REASSIGN) {
            if (!me.equals(assignments.currentHolder(tenant.hotelId(), tableId))) {
                throw DomainException.conflict("You can only hand over a table that is yours");
            }
            if (target != null && (target.equals(me) || !assignments.canServe(tenant.hotelId(), target))) {
                throw validation("That person cannot take this table");
            }
        }

        try {
            WaiterRequest saved = requests.insert(new WaiterRequest(tenant.hotelId(), tableId, table.number(),
                    body.kind(), me, note, target, clock.instant()));
            audit.record(tenant.hotelId(),
                    body.kind() == RequestKind.HELP ? WaiterEventType.HELP_REQUESTED
                            : WaiterEventType.REASSIGN_REQUESTED,
                    tableId, table.number(), null, me, me, target, note);
            return new Raised(view(saved), true);
        } catch (DuplicateKeyException alreadyOpen) {
            WaiterRequest existing = requests.findByTableIdAndRequestedByUserIdAndKindAndStatus(
                            tableId, me, body.kind(), RequestStatus.OPEN)
                    .orElseThrow(() -> DomainException.conflict("This request was just changed, please try again"));
            return new Raised(view(existing), false);
        }
    }

    // ---------- lists ----------

    /** Managers see every open request. Waiters see all open help requests and only their own hand-overs. */
    public List<WaiterRequestResponse> listOpen(TenantContext tenant) {
        boolean manager = tenant.can(Permission.MANAGE_TABLES);
        if (!manager && !tenant.can(Permission.DELIVER_ORDERS)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "You are not allowed to do that");
        }
        List<WaiterRequest> open = requests.findFirst200ByHotelIdAndStatusOrderByCreatedAtAscIdAsc(
                tenant.hotelId(), RequestStatus.OPEN);
        return views(open.stream()
                .filter(r -> manager || r.getKind() == RequestKind.HELP
                        || tenant.userId().equals(r.getRequestedByUserId()))
                .toList());
    }

    public List<WaiterRequestResponse> mine(TenantContext tenant) {
        tenant.require(Permission.DELIVER_ORDERS);
        return views(requests.findFirst20ByHotelIdAndRequestedByUserIdOrderByCreatedAtDescIdDesc(
                tenant.hotelId(), tenant.userId()));
    }

    // ---------- help ----------

    public WaiterRequestResponse respond(TenantContext tenant, String requestId) {
        tenant.require(Permission.DELIVER_ORDERS);
        String me = tenant.userId();
        WaiterRequest request = load(tenant, requestId);
        if (request.getKind() != RequestKind.HELP) {
            throw DomainException.conflict("Hand-over requests are approved by a manager");
        }
        if (me.equals(request.getRequestedByUserId())) {
            throw validation("You cannot answer your own request");
        }

        WaiterRequest accepted = decide(tenant, requestId, RequestStatus.ACCEPTED, null, null);
        if (accepted != null) {
            audit.record(tenant.hotelId(), WaiterEventType.HELP_ACCEPTED, accepted.getTableId(),
                    accepted.getTableNumber(), null, me, accepted.getRequestedByUserId(), me, null);
            return view(accepted);
        }
        WaiterRequest current = load(tenant, requestId);
        if (current.getStatus() == RequestStatus.ACCEPTED && me.equals(current.getDecidedByUserId())) {
            return view(current);
        }
        throw DomainException.conflict(current.getStatus() == RequestStatus.ACCEPTED
                ? "Someone else is already helping" : "This request is no longer open");
    }

    // ---------- cancel ----------

    public WaiterRequestResponse cancel(TenantContext tenant, String requestId) {
        tenant.require(Permission.DELIVER_ORDERS);
        String me = tenant.userId();
        WaiterRequest request = load(tenant, requestId);
        if (!tenant.can(Permission.MANAGE_TABLES) && !me.equals(request.getRequestedByUserId())) {
            throw new DomainException(ErrorCode.FORBIDDEN, "You are not allowed to do that");
        }

        WaiterRequest cancelled = decide(tenant, requestId, RequestStatus.CANCELLED, null, null);
        if (cancelled != null) {
            audit.record(tenant.hotelId(), WaiterEventType.REQUEST_CANCELLED, cancelled.getTableId(),
                    cancelled.getTableNumber(), null, me, cancelled.getRequestedByUserId(), null, null);
            return view(cancelled);
        }
        WaiterRequest current = load(tenant, requestId);
        if (current.getStatus() == RequestStatus.CANCELLED) {
            return view(current);
        }
        throw DomainException.conflict("This request is already " + current.getStatus().name().toLowerCase());
    }

    // ---------- hand-over ----------

    public WaiterRequestResponse approve(TenantContext tenant, String requestId, String toUserId) {
        tenant.require(Permission.MANAGE_TABLES);
        String me = tenant.userId();
        WaiterRequest request = load(tenant, requestId);
        if (request.getKind() != RequestKind.REASSIGN) {
            throw DomainException.conflict("Help requests are answered by waiters, they are not approved");
        }
        if (request.getStatus() == RequestStatus.APPROVED) {
            return view(request);
        }
        if (request.getStatus() != RequestStatus.OPEN) {
            throw DomainException.conflict("This request is already " + request.getStatus().name().toLowerCase());
        }

        String chosen = clean(toUserId);
        String target = chosen != null ? chosen : request.getTargetUserId();
        if (target == null) {
            throw validation("Choose who should take this table");
        }
        if (target.equals(request.getRequestedByUserId()) || !assignments.canServe(tenant.hotelId(), target)) {
            throw validation("That person cannot take this table");
        }

        String requester = request.getRequestedByUserId();
        if (!requester.equals(assignments.currentHolder(tenant.hotelId(), request.getTableId()))) {
            decide(tenant, requestId, RequestStatus.VOID, null, null);
            throw DomainException.conflict("This table is no longer assigned to the person who asked");
        }

        WaiterRequest approved = decide(tenant, requestId, RequestStatus.APPROVED, target, null);
        if (approved == null) {
            WaiterRequest current = load(tenant, requestId);
            if (current.getStatus() == RequestStatus.APPROVED) {
                return view(current);
            }
            throw DomainException.conflict("This request is no longer open");
        }
        if (!assignments.handOver(tenant.hotelId(), request.getTableId(), requester, target, me)) {
            mongo.updateFirst(Query.query(Criteria.where("id").is(requestId).and("status")
                            .is(RequestStatus.APPROVED)),
                    new Update().set("status", RequestStatus.VOID), WaiterRequest.class);
            throw DomainException.conflict("This table changed hands just now, so the request was closed");
        }
        audit.record(tenant.hotelId(), WaiterEventType.REASSIGN_APPROVED, request.getTableId(),
                request.getTableNumber(), null, me, requester, target, request.getNote());
        return view(approved);
    }

    public WaiterRequestResponse decline(TenantContext tenant, String requestId, String reason) {
        tenant.require(Permission.MANAGE_TABLES);
        WaiterRequest request = load(tenant, requestId);
        if (request.getKind() != RequestKind.REASSIGN) {
            throw DomainException.conflict("Only hand-over requests are declined; help requests can be cancelled");
        }
        WaiterRequest declined = decide(tenant, requestId, RequestStatus.DECLINED, null, clean(reason));
        if (declined != null) {
            audit.record(tenant.hotelId(), WaiterEventType.REASSIGN_DECLINED, declined.getTableId(),
                    declined.getTableNumber(), null, tenant.userId(), declined.getRequestedByUserId(), null,
                    declined.getDecisionNote());
            return view(declined);
        }
        WaiterRequest current = load(tenant, requestId);
        if (current.getStatus() == RequestStatus.DECLINED) {
            return view(current);
        }
        throw DomainException.conflict("This request is already " + current.getStatus().name().toLowerCase());
    }

    // ---------- helpers ----------

    /** The one atomic decision: only an OPEN request of this hotel can be decided, once. */
    private WaiterRequest decide(TenantContext tenant, String requestId, RequestStatus next, String assignedTo,
                                 String decisionNote) {
        Update update = new Update().set("status", next).set("decidedByUserId", tenant.userId())
                .set("decidedAt", clock.instant());
        if (assignedTo != null) {
            update.set("assignedToUserId", assignedTo);
        }
        if (decisionNote != null) {
            update.set("decisionNote", decisionNote);
        }
        return mongo.findAndModify(
                Query.query(Criteria.where("id").is(requestId).and("hotelId").is(tenant.hotelId())
                        .and("status").is(RequestStatus.OPEN)),
                update, FindAndModifyOptions.options().returnNew(true), WaiterRequest.class);
    }

    private WaiterRequest load(TenantContext tenant, String requestId) {
        return requests.findByIdAndHotelId(requestId, tenant.hotelId())
                .orElseThrow(() -> DomainException.notFound("Request"));
    }

    private static DomainException validation(String message) {
        return new DomainException(ErrorCode.VALIDATION_FAILED, message);
    }

    private static String clean(String text) {
        if (text == null) {
            return null;
        }
        String stripped = text.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private WaiterRequestResponse view(WaiterRequest request) {
        return views(List.of(request)).get(0);
    }

    private List<WaiterRequestResponse> views(List<WaiterRequest> rows) {
        Set<String> ids = new HashSet<>();
        for (WaiterRequest r : rows) {
            addIfPresent(ids, r.getRequestedByUserId());
            addIfPresent(ids, r.getTargetUserId());
            addIfPresent(ids, r.getDecidedByUserId());
            addIfPresent(ids, r.getAssignedToUserId());
        }
        Map<String, AccountSummary> people = accounts.summaries(ids);
        return rows.stream().map(r -> new WaiterRequestResponse(r.getId(), r.getKind(), r.getStatus(),
                r.getTableId(), r.getTableNumber(), r.getRequestedByUserId(), name(people, r.getRequestedByUserId()),
                r.getNote(), r.getTargetUserId(), name(people, r.getTargetUserId()), r.getDecidedByUserId(),
                name(people, r.getDecidedByUserId()), r.getAssignedToUserId(),
                name(people, r.getAssignedToUserId()), r.getDecisionNote(), r.getCreatedAt(),
                r.getDecidedAt())).toList();
    }

    private static void addIfPresent(Set<String> ids, String id) {
        if (id != null) {
            ids.add(id);
        }
    }

    private static String name(Map<String, AccountSummary> people, String id) {
        AccountSummary person = id == null ? null : people.get(id);
        return person == null ? null : person.name();
    }
}