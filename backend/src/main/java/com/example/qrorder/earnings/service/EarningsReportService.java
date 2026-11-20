package com.example.qrorder.earnings.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.common.page.PageResponse;
import com.example.qrorder.earnings.config.EarningsProperties;
import com.example.qrorder.earnings.dto.EarningsEntryView;
import com.example.qrorder.earnings.dto.EarningsSummary;
import com.example.qrorder.earnings.dto.HotelBalanceRow;
import com.example.qrorder.earnings.model.EarningsEntry;
import com.example.qrorder.earnings.model.EarningsEntryType;
import com.example.qrorder.earnings.model.EarningsSettings;
import com.example.qrorder.earnings.model.FeeSource;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.Permission;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Comparator;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** Reads the earnings ledger: a hotel's own report, and the platform's view of every hotel's balance. */
@Service
public class EarningsReportService {

    static final int MAX_DAYS = 93;
    static final int MAX_SIZE = 100;

    private final MongoTemplate mongo;
    private final EarningsProperties props;
    private final Clock clock;

    public EarningsReportService(MongoTemplate mongo, EarningsProperties props, Clock clock) {
        this.mongo = mongo;
        this.props = props;
        this.clock = clock;
    }

    // ---------- one hotel ----------

    public EarningsSummary summary(TenantContext tenant, LocalDate from, LocalDate to) {
        tenant.require(Permission.MANAGE_HOTEL);
        return summaryFor(tenant.hotelId(), from, to);
    }

    /** Also used by the platform admin, who has already been checked by the caller. Defaults to the last 30 days. */
    public EarningsSummary summaryFor(String hotelId, LocalDate from, LocalDate to) {
        Hotel hotel = mongo.findById(hotelId, Hotel.class);
        if (hotel == null) {
            throw DomainException.notFound("Hotel");
        }
        ZoneId zone = ZoneId.of(hotel.getTimezone());
        LocalDate end = to != null ? to : LocalDate.now(clock.withZone(zone));
        LocalDate start = from != null ? from : end.minusDays(29);
        if (start.isAfter(end)) {
            throw invalid("from must not be after to");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) {
            throw invalid("Choose at most " + MAX_DAYS + " days");
        }
        Instant fromInstant = start.atStartOfDay(zone).toInstant();
        Instant toInstant = end.plusDays(1).atStartOfDay(zone).toInstant();

        List<Document> pipeline = List.of(
                new Document("$match", new Document("hotelId", hotelId).append("occurredAt",
                        new Document("$gte", Date.from(fromInstant)).append("$lt", Date.from(toInstant)))),
                new Document("$group", new Document("_id", new Document("day", new Document("$dateToString",
                        new Document("format", "%Y-%m-%d").append("date", "$occurredAt").append("timezone", zone.getId())))
                        .append("type", "$type"))
                        .append("gross", new Document("$sum", "$grossMinor"))
                        .append("fee", new Document("$sum", "$feeMinor"))
                        .append("commission", new Document("$sum", "$commissionMinor"))
                        .append("net", new Document("$sum", "$netMinor"))
                        .append("n", new Document("$sum", 1))));

        Map<String, long[]> perDay = new TreeMap<>(); // collected, refunded, fee, commission, payouts, net
        long payments = 0;
        for (Document row : mongo.getCollection(mongo.getCollectionName(EarningsEntry.class)).aggregate(pipeline)) {
            Document id = row.get("_id", Document.class);
            String day = id.getString("day");
            EarningsEntryType type = EarningsEntryType.valueOf(id.getString("type"));
            long[] acc = perDay.computeIfAbsent(day, k -> new long[6]);
            long gross = number(row, "gross");
            long net = number(row, "net");
            acc[2] += number(row, "fee");
            acc[3] += number(row, "commission");
            switch (type) {
                case ONLINE_PAYMENT -> {
                    acc[0] += gross;
                    acc[5] += net;
                    payments += number(row, "n");
                }
                case REFUND -> {
                    acc[1] += -gross;
                    acc[5] += net;
                }
                case PAYOUT -> acc[4] += -net;
            }
        }

        List<EarningsSummary.Day> days = new ArrayList<>();
        long[] total = new long[6];
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            long[] acc = perDay.getOrDefault(d.toString(), new long[6]);
            for (int i = 0; i < 6; i++) {
                total[i] += acc[i];
            }
            days.add(new EarningsSummary.Day(d, acc[0], acc[1], acc[2], acc[3], acc[4], acc[5]));
        }

        EarningsSummary.Totals totals = new EarningsSummary.Totals(payments, total[0], total[1], total[2], total[3],
                total[4], total[5]);
        long withoutFee = mongo.count(Query.query(Criteria.where("hotelId").is(hotelId)
                        .and("type").is(EarningsEntryType.ONLINE_PAYMENT).and("feeSource").ne(FeeSource.WEBHOOK)),
                EarningsEntry.class);
        return new EarningsSummary(hotelId, hotel.getCurrency(), zone.getId(), start, end, totals, balance(hotelId),
                withoutFee, days);
    }

    /** What the platform owes the hotel right now (all time). */
    public long balance(String hotelId) {
        List<Document> pipeline = List.of(
                new Document("$match", new Document("hotelId", hotelId)),
                new Document("$group", new Document("_id", null).append("balance", new Document("$sum", "$netMinor"))));
        for (Document row : mongo.getCollection(mongo.getCollectionName(EarningsEntry.class)).aggregate(pipeline)) {
            return number(row, "balance");
        }
        return 0;
    }

    public PageResponse<EarningsEntryView> entries(TenantContext tenant, EarningsEntryType type, int page, int size) {
        tenant.require(Permission.MANAGE_HOTEL);
        checkPage(page, size);
        Criteria criteria = Criteria.where("hotelId").is(tenant.hotelId());
        if (type != null) {
            criteria = criteria.and("type").is(type);
        }
        long total = mongo.count(Query.query(criteria), EarningsEntry.class);
        List<EarningsEntryView> items = mongo.find(Query.query(criteria)
                .with(Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")))
                .skip((long) page * size).limit(size), EarningsEntry.class).stream().map(EarningsEntryView::from).toList();
        return PageResponse.of(items, page, size, total);
    }

    // ---------- all hotels (platform admin) ----------

    /** Every hotel that has any ledger entry, largest balance first. The caller has already checked for the admin role. */
    public PageResponse<HotelBalanceRow> balances(int page, int size) {
        checkPage(page, size);
        // same simple grouping as the owner report: one row per hotel and entry type
        List<Document> pipeline = List.of(
                new Document("$group", new Document("_id",
                        new Document("hotelId", "$hotelId").append("type", "$type"))
                        .append("gross", new Document("$sum", "$grossMinor"))
                        .append("net", new Document("$sum", "$netMinor"))));

        Map<String, long[]> perHotel = new HashMap<>(); // collected, payouts, balance
        for (Document row : mongo.getCollection(mongo.getCollectionName(EarningsEntry.class)).aggregate(pipeline)) {
            Document id = row.get("_id", Document.class);
            String hotelId = id.getString("hotelId");
            if (hotelId == null) {
                continue;
            }
            EarningsEntryType type = EarningsEntryType.valueOf(id.getString("type"));
            long[] acc = perHotel.computeIfAbsent(hotelId, k -> new long[3]);
            long gross = number(row, "gross");
            long net = number(row, "net");
            acc[2] += net;
            if (type == EarningsEntryType.ONLINE_PAYMENT) {
                acc[0] += gross;
            } else if (type == EarningsEntryType.PAYOUT) {
                acc[1] += -net;
            }
        }

        List<String> ordered = perHotel.keySet().stream()
                .sorted(Comparator.<String>comparingLong(k -> perHotel.get(k)[2]).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
        int from = (int) Math.min((long) page * size, ordered.size());
        int to = Math.min(ordered.size(), from + size);
        List<String> ids = ordered.subList(from, to);

        Map<String, Hotel> hotels = new HashMap<>();
        mongo.find(Query.query(Criteria.where("id").in(ids)), Hotel.class).forEach(h -> hotels.put(h.getId(), h));
        Map<String, Integer> custom = new HashMap<>();
        mongo.find(Query.query(Criteria.where("hotelId").in(ids)), EarningsSettings.class)
                .forEach(s -> custom.put(s.getHotelId(), s.getCommissionBps()));

        List<HotelBalanceRow> items = ids.stream().map(id -> {
            Hotel h = hotels.get(id);
            long[] acc = perHotel.get(id);
            return new HotelBalanceRow(id, h == null ? null : h.getName(), h == null ? null : h.getSlug(),
                    h == null ? null : h.getCurrency(), custom.getOrDefault(id, props.defaultCommissionBps()),
                    acc[0], acc[1], acc[2]);
        }).toList();
        return PageResponse.of(items, page, size, ordered.size());
    }


    // ---------- helpers ----------

    private static long number(Document doc, String key) {
        Object value = doc.get(key);
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static void checkPage(int page, int size) {
        if (page < 0) {
            throw invalid("page must be 0 or more");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw invalid("size must be between 1 and " + MAX_SIZE);
        }
    }

    private static DomainException invalid(String message) {
        return new DomainException(ErrorCode.VALIDATION_FAILED, message);
    }
}