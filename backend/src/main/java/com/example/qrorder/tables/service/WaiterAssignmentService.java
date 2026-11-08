package com.example.qrorder.tables.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.identity.service.AccountService;
import com.example.qrorder.identity.service.AccountSummary;
import com.example.qrorder.tables.dto.TableResponse;
import com.example.qrorder.tables.dto.WaiterAssignmentResponse;
import com.example.qrorder.tables.model.TableAssignment;
import com.example.qrorder.tables.model.WaiterEventType;
import com.example.qrorder.tables.repository.TableAssignmentRepository;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import com.example.qrorder.tenancy.service.StaffDirectory;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class WaiterAssignmentService {

    private final TableAssignmentRepository assignments;
    private final MongoTemplate mongo;
    private final TableService tables;
    private final StaffDirectory directory;
    private final AccountService accounts;
    private final WaiterAuditService audit;
    private final Clock clock;

    public WaiterAssignmentService(TableAssignmentRepository assignments, MongoTemplate mongo, TableService tables,
                                   StaffDirectory directory, AccountService accounts, WaiterAuditService audit,Clock clock) {
        this.assignments = assignments;
        this.mongo = mongo;
        this.tables = tables;
        this.directory = directory;
        this.accounts = accounts;
        this.audit = audit;
        this.clock = clock;
    }

    /** Manager override: sets the waiter whether or not the table already has one. */
    public WaiterAssignmentResponse assign(TenantContext tenant, String tableId, String userId) {
        tenant.require(Permission.MANAGE_TABLES);
        TableResponse table = activeTable(tenant, tableId);
        if (!canServe(tenant.hotelId(), userId)) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "That person cannot be assigned to tables");
        }
        String previous = assignments.findByTableId(tableId)
                .filter(a -> a.getHotelId().equals(tenant.hotelId()))
                .map(TableAssignment::getWaiterUserId).orElse(null);
        TableAssignment saved = upsert(tenant, tableId, userId);
        if (!userId.equals(previous)) {
            audit.record(tenant.hotelId(), WaiterEventType.ASSIGNED, tableId, table.number(), null,
                    tenant.userId(), previous, userId, null);
        }
        return row(table, saved, nameOf(userId));
    }

    /** Clears the assignment. Repeating it is harmless. */
    public void unassign(TenantContext tenant, String tableId) {
        tenant.require(Permission.MANAGE_TABLES);
        String previous = assignments.findByTableId(tableId)
                .filter(a -> a.getHotelId().equals(tenant.hotelId()))
                .map(TableAssignment::getWaiterUserId).orElse(null);
        long deleted = mongo.remove(
                Query.query(Criteria.where("tableId").is(tableId).and("hotelId").is(tenant.hotelId())),
                TableAssignment.class).getDeletedCount();
        if (deleted > 0) {
            audit.record(tenant.hotelId(), WaiterEventType.UNASSIGNED, tableId, numberOf(tenant, tableId), null,
                    tenant.userId(), previous, null, null);
        }
    }

    /**
     * A waiter takes a free table. The unique index decides a race: one insert wins, the other gets 409.
     * An assignment whose waiter no longer works here (removed, or no longer a waiter) is taken over.
     */
    public WaiterAssignmentResponse claim(TenantContext tenant, String tableId) {
        tenant.require(Permission.DELIVER_ORDERS);
        TableResponse table = activeTable(tenant, tableId);
        String me = tenant.userId();
        try {
            TableAssignment created = assignments.insert(
                    new TableAssignment(tenant.hotelId(), tableId, me, me, clock.instant()));
            audit.record(tenant.hotelId(), WaiterEventType.CLAIMED, tableId, table.number(), null, me, null, me,
                    null);
            return row(table, created, nameOf(me));
        } catch (DuplicateKeyException taken) {
            TableAssignment existing = assignments.findByTableId(tableId)
                    .filter(a -> a.getHotelId().equals(tenant.hotelId()))
                    .orElseThrow(() -> DomainException.conflict("This table was just changed, please try again"));
            if (existing.getWaiterUserId().equals(me)) {
                return row(table, existing, nameOf(me)); // already yours
            }
            Set<String> current = directory.userIdsWith(tenant.hotelId(), Permission.DELIVER_ORDERS);
            if (current.contains(existing.getWaiterUserId())) {
                throw DomainException.conflict("This table is already taken by "
                        + nameOrFallback(nameOf(existing.getWaiterUserId())));
            }
            TableAssignment takenOver = mongo.findAndModify(
                    Query.query(Criteria.where("tableId").is(tableId)
                            .and("waiterUserId").is(existing.getWaiterUserId())),
                    new Update().set("waiterUserId", me).set("assignedByUserId", me).set("assignedAt", clock.instant()),
                    FindAndModifyOptions.options().returnNew(true), TableAssignment.class);
            if (takenOver == null) {
                throw DomainException.conflict("This table was just taken, please try again");
            }
            audit.record(tenant.hotelId(), WaiterEventType.TAKEN_OVER, tableId, table.number(), null, me,
                    existing.getWaiterUserId(), me, null);
            return row(table, takenOver, nameOf(me));
        }
    }

    /** A waiter gives up their own table. Someone else's table is 409 (a manager uses unassign). */
    public void release(TenantContext tenant, String tableId) {
        tenant.require(Permission.DELIVER_ORDERS);
        long removed = mongo.remove(Query.query(Criteria.where("tableId").is(tableId)
                .and("hotelId").is(tenant.hotelId()).and("waiterUserId").is(tenant.userId())),
                TableAssignment.class).getDeletedCount();
        if (removed > 0) {
            audit.record(tenant.hotelId(), WaiterEventType.RELEASED, tableId, numberOf(tenant, tableId), null,
                    tenant.userId(), tenant.userId(), null, null);
            return;
        }
        Optional<TableAssignment> other = assignments.findByTableId(tableId)
                .filter(a -> a.getHotelId().equals(tenant.hotelId()));
        if (other.isPresent()) {
            throw DomainException.conflict("This table is assigned to someone else");
        }
    }

    /** Every active table in natural order, with its waiter (or nulls when free). */
    public List<WaiterAssignmentResponse> list(TenantContext tenant) {
        if (!tenant.can(Permission.MANAGE_TABLES) && !tenant.can(Permission.DELIVER_ORDERS)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "You are not allowed to do that");
        }
        Map<String, TableAssignment> byTable = assignments.findByHotelId(tenant.hotelId()).stream()
                .collect(Collectors.toMap(TableAssignment::getTableId, Function.identity(), (a, b) -> a));
        Set<String> validWaiters = directory.userIdsWith(tenant.hotelId(), Permission.DELIVER_ORDERS);
        Map<String, AccountSummary> people = accounts.summaries(new HashSet<>(byTable.values().stream()
                .map(TableAssignment::getWaiterUserId).filter(validWaiters::contains).toList()));

        List<WaiterAssignmentResponse> rows = new ArrayList<>();
        for (TableResponse table : tables.list(tenant, false)) {
            TableAssignment assignment = byTable.get(table.id());
            if (assignment != null && validWaiters.contains(assignment.getWaiterUserId())) {
                AccountSummary person = people.get(assignment.getWaiterUserId());
                rows.add(row(table, assignment, person == null ? null : person.name()));
            } else {
                rows.add(new WaiterAssignmentResponse(table.id(), table.number(), table.section(), null, null, null));
            }
        }
        return rows;
    }

    public List<WaiterAssignmentResponse> mine(TenantContext tenant) {
        tenant.require(Permission.DELIVER_ORDERS);
        return list(tenant).stream().filter(r -> tenant.userId().equals(r.waiterUserId())).toList();
    }

    /** table id → id of the waiter currently responsible. Assignments to people who no longer serve are left out. */
    public Map<String, String> responsibleWaiters(String hotelId) {
        Set<String> valid = directory.userIdsWith(hotelId, Permission.DELIVER_ORDERS);
        return assignments.findByHotelId(hotelId).stream()
                .filter(a -> valid.contains(a.getWaiterUserId()))
                .collect(Collectors.toMap(TableAssignment::getTableId, TableAssignment::getWaiterUserId,
                        (a, b) -> a));
    }

    // ---------- helpers ----------
    public TableResponse activeTable(TenantContext tenant, String tableId) {
        return tables.list(tenant, false).stream()
                .filter(t -> t.id().equals(tableId))
                .findFirst()
                .orElseThrow(() -> DomainException.notFound("Table"));
    }

    public String currentHolder(String hotelId, String tableId) {
        return assignments.findByTableId(tableId)
                .filter(a -> a.getHotelId().equals(hotelId))
                .map(TableAssignment::getWaiterUserId)
                .filter(id -> directory.userIdsWith(hotelId, Permission.DELIVER_ORDERS).contains(id))
                .orElse(null);
    }

    public boolean canServe(String hotelId, String userId) {
        return directory.findActive(hotelId, userId)
                .map(staff -> staff.permissions().contains(Permission.DELIVER_ORDERS)).orElse(false);
    }

    public boolean handOver(String hotelId, String tableId, String fromUserId, String toUserId, String decidedBy) {
        return mongo.findAndModify(
                Query.query(Criteria.where("tableId").is(tableId).and("hotelId").is(hotelId)
                        .and("waiterUserId").is(fromUserId)),
                new Update().set("waiterUserId", toUserId).set("assignedByUserId", decidedBy)
                        .set("assignedAt", clock.instant()),
                FindAndModifyOptions.options().returnNew(true), TableAssignment.class) != null;
    }

    private TableAssignment upsert(TenantContext tenant, String tableId, String userId) {
        for (int attempt = 0; ; attempt++) {
            try {
                return mongo.findAndModify(
                        Query.query(Criteria.where("tableId").is(tableId)),
                        new Update().set("hotelId", tenant.hotelId()).set("waiterUserId", userId)
                                .set("assignedByUserId", tenant.userId()).set("assignedAt", clock.instant()),
                        FindAndModifyOptions.options().upsert(true).returnNew(true), TableAssignment.class);
            } catch (DuplicateKeyException firstInsertCollided) {
                if (attempt >= 1) {
                    throw firstInsertCollided; // the second try takes the update path, so this is not expected
                }
            }
        }
    }

    private String numberOf(TenantContext tenant, String tableId) {
        return tables.list(tenant, false).stream()
                .filter(t -> t.id().equals(tableId))
                .map(TableResponse::number)
                .findFirst().orElse(null);
    }

    private String nameOf(String userId) {
        AccountSummary summary = accounts.summaries(List.of(userId)).get(userId);
        return summary == null ? null : summary.name();
    }

    private static String nameOrFallback(String name) {
        return name == null ? "another waiter" : name;
    }

    private static WaiterAssignmentResponse row(TableResponse table, TableAssignment assignment, String waiterName) {
        return new WaiterAssignmentResponse(table.id(), table.number(), table.section(),
                assignment.getWaiterUserId(), waiterName, assignment.getAssignedAt());
    }
}