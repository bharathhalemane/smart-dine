package com.example.qrorder.admin.service;

import com.example.qrorder.admin.dto.AdminHotelDetail;
import com.example.qrorder.admin.dto.AdminHotelSummary;
import com.example.qrorder.admin.dto.OwnerInfo;
import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.common.page.PageResponse;
import com.example.qrorder.identity.service.AccountDirectory;
import com.example.qrorder.identity.service.AccountService;
import com.example.qrorder.identity.service.AccountSummary;
import com.example.qrorder.tables.model.DiningTable;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelMembership;
import com.example.qrorder.tenancy.model.HotelStatus;
import com.example.qrorder.tenancy.model.MembershipStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * The platform admin's view of all hotels. Only reached through /api/v1/admin/**, which SecurityConfig
 * restricts to platform admins.
 */
@Service
public class AdminHotelService {

    static final int MAX_SIZE = 100;
    static final int MAX_QUERY_LENGTH = 100;
    private static final int MAX_OWNER_MATCHES = 50;
    private static final Set<String> SORT_FIELDS = Set.of("createdAt", "name", "slug", "status");

    private final MongoTemplate mongo;
    private final AccountService accounts;
    private final AccountDirectory directory;

    public AdminHotelService(MongoTemplate mongo, AccountService accounts, AccountDirectory directory) {
        this.mongo = mongo;
        this.accounts = accounts;
        this.directory = directory;
    }

    public PageResponse<AdminHotelSummary> search(String q, HotelStatus status, Instant createdAfter,
                                                  Instant createdBefore, int page, int size, String sort) {
        if (page < 0) {
            throw invalid("page cannot be negative");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw invalid("size must be between 1 and " + MAX_SIZE);
        }
        Sort order = parseSort(sort);
        Criteria criteria = criteria(q, status, createdAfter, createdBefore);

        long total = mongo.count(new Query(criteria), Hotel.class);
        List<Hotel> hotels = mongo.find(new Query(criteria).with(PageRequest.of(page, size, order)), Hotel.class);

        List<String> ids = hotels.stream().map(Hotel::getId).toList();
        Map<String, Integer> staff = countsByHotel(HotelMembership.class, ids,
                Criteria.where("status").is(MembershipStatus.ACTIVE));
        Map<String, Integer> tableCounts = countsByHotel(DiningTable.class, ids, Criteria.where("active").is(true));
        Map<String, AccountSummary> owners = owners(hotels);

        List<AdminHotelSummary> items = hotels.stream().map(h -> new AdminHotelSummary(h.getId(), h.getSlug(),
                h.getName(), h.getAddress(), h.getStatus(), h.getStatusReason(), owner(owners, h.getOwnerUserId()),
                staff.getOrDefault(h.getId(), 0), tableCounts.getOrDefault(h.getId(), 0), h.getCreatedAt())).toList();
        return PageResponse.of(items, page, size, total);
    }

    public AdminHotelDetail get(String hotelId) {
        Hotel h = mongo.findById(hotelId, Hotel.class);
        if (h == null) {
            throw DomainException.notFound("Hotel");
        }
        List<String> ids = List.of(h.getId());
        int staff = countsByHotel(HotelMembership.class, ids, Criteria.where("status").is(MembershipStatus.ACTIVE))
                .getOrDefault(h.getId(), 0);
        int tableCount = countsByHotel(DiningTable.class, ids, Criteria.where("active").is(true))
                .getOrDefault(h.getId(), 0);
        return new AdminHotelDetail(h.getId(), h.getSlug(), h.getName(), h.getAddress(), h.getTimezone(),
                h.getCurrency(), h.getStatus(), h.getStatusReason(), owner(owners(List.of(h)), h.getOwnerUserId()),
                staff, tableCount, h.isWaitersMayClearTables(), h.isCashiersMayAdjustBills(), h.getCreatedAt(),
                h.getUpdatedAt());
    }

    // ---------- query building ----------

    private Criteria criteria(String q, HotelStatus status, Instant createdAfter, Instant createdBefore) {
        List<Criteria> all = new ArrayList<>();
        if (status != null) {
            all.add(Criteria.where("status").is(status));
        }
        if (createdAfter != null) {
            all.add(Criteria.where("createdAt").gte(createdAfter));
        }
        if (createdBefore != null) {
            all.add(Criteria.where("createdAt").lt(createdBefore));
        }
        String text = q == null ? null : q.strip();
        if (text != null && !text.isEmpty()) {
            if (text.length() > MAX_QUERY_LENGTH) {
                throw invalid("q can be at most " + MAX_QUERY_LENGTH + " characters");
            }
            Pattern contains = Pattern.compile(escape(text), Pattern.CASE_INSENSITIVE);
            List<Criteria> any = new ArrayList<>();
            any.add(Criteria.where("name").regex(contains));
            any.add(Criteria.where("slug").regex(contains));
            any.add(Criteria.where("address").regex(contains));
            any.add(Criteria.where("id").is(text));
            List<String> ownerIds = directory.idsByEmailContaining(text, MAX_OWNER_MATCHES);
            if (!ownerIds.isEmpty()) {
                any.add(Criteria.where("ownerUserId").in(ownerIds));
            }
            all.add(new Criteria().orOperator(any));
        }
        return all.isEmpty() ? new Criteria() : new Criteria().andOperator(all);
    }

    /** "field" or "field,asc|desc"; only known fields. Ties are broken by id so pages never overlap. */
    private static Sort parseSort(String sort) {
        String[] parts = (sort == null || sort.isBlank() ? "createdAt,desc" : sort).split(",");
        String field = parts[0].strip();
        if (parts.length > 2 || !SORT_FIELDS.contains(field)) {
            throw invalid("sort must be one of " + SORT_FIELDS + ", optionally followed by ,asc or ,desc");
        }
        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length == 2) {
            String d = parts[1].strip().toLowerCase();
            if (d.equals("desc")) {
                direction = Sort.Direction.DESC;
            } else if (!d.equals("asc")) {
                throw invalid("sort direction must be asc or desc");
            }
        } else if (field.equals("createdAt")) {
            direction = Sort.Direction.DESC; // newest first unless asked otherwise
        }
        return Sort.by(direction, field).and(Sort.by(Sort.Direction.ASC, "id"));
    }

    static String escape(String text) {
        return text.replaceAll("[\\\\^$.|?*+()\\[\\]{}]", "\\\\$0");
    }

    // ---------- the extra columns ----------

    /** One grouped query for a whole page: how many documents per hotel match. */
    private Map<String, Integer> countsByHotel(Class<?> type, Collection<String> hotelIds, Criteria extra) {
        Map<String, Integer> counts = new HashMap<>();
        if (hotelIds.isEmpty()) {
            return counts;
        }
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("hotelId").in(hotelIds).andOperator(extra)),
                Aggregation.group("hotelId").count().as("n"));
        for (Document row : mongo.aggregate(aggregation, type, Document.class).getMappedResults()) {
            counts.put(row.getString("_id"), ((Number) row.get("n")).intValue());
        }
        return counts;
    }

    private Map<String, AccountSummary> owners(Collection<Hotel> hotels) {
        Set<String> ids = new HashSet<>();
        for (Hotel h : hotels) {
            if (h.getOwnerUserId() != null) {
                ids.add(h.getOwnerUserId());
            }
        }
        return ids.isEmpty() ? Map.of() : accounts.summaries(ids);
    }

    private static OwnerInfo owner(Map<String, AccountSummary> owners, String ownerUserId) {
        AccountSummary summary = ownerUserId == null ? null : owners.get(ownerUserId);
        return summary == null ? new OwnerInfo(ownerUserId, null, null)
                : new OwnerInfo(summary.id(), summary.name(), summary.email());
    }

    private static DomainException invalid(String message) {
        return new DomainException(ErrorCode.VALIDATION_FAILED, message);
    }
}