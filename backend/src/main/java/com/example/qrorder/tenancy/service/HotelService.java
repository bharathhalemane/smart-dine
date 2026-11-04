package com.example.qrorder.tenancy.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.identity.service.AccountService;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.dto.CreateHotelRequest;
import com.example.qrorder.tenancy.dto.HotelResponse;
import com.example.qrorder.tenancy.dto.MyHotelResponse;
import com.example.qrorder.tenancy.dto.UpdateHotelRequest;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelMembership;
import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.model.MembershipStatus;
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HotelService {

    private static final String DEFAULT_TIMEZONE = "Asia/Kolkata";
    private static final String DEFAULT_CURRENCY = "INR";

    private final HotelRepository hotels;
    private final HotelMembershipRepository memberships;
    private final AccountService accounts;
    private final Clock clock;

    public HotelService(HotelRepository hotels, HotelMembershipRepository memberships,
                        AccountService accounts, Clock clock) {
        this.hotels = hotels;
        this.memberships = memberships;
        this.accounts = accounts;
        this.clock = clock;
    }

    /** Hotel and owner membership are written in one transaction: never one without the other. */
    @Transactional
    public HotelResponse create(String userId, CreateHotelRequest request) {
        if (!accounts.isEmailVerified(userId)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Verify your email address before creating a hotel");
        }
        String name = request.name().trim();
        if (name.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "Hotel name must not be blank");
        }
        String timezone = request.timezone() == null || request.timezone().isBlank()
                ? DEFAULT_TIMEZONE
                : validZone(request.timezone());
        Instant now = clock.instant();

        try {
            Hotel hotel = hotels.save(new Hotel(uniqueSlug(name), name, userId, trimToNull(request.address()),
                    timezone, DEFAULT_CURRENCY, now));
            memberships.save(new HotelMembership(hotel.getId(), userId, Set.of(HotelRole.OWNER), userId, now));
            return HotelResponse.from(hotel);
        } catch (DuplicateKeyException e) {
            // Two hotels picked the same slug at the same moment; the transaction rolls back.
            throw DomainException.conflict("Could not create the hotel, please try again");
        }
    }

    public List<MyHotelResponse> myHotels(String userId) {
        List<HotelMembership> mine = memberships.findByUserIdAndStatus(userId, MembershipStatus.ACTIVE);
        Map<String, Hotel> byId = hotels.findAllById(mine.stream().map(HotelMembership::getHotelId).toList())
                .stream().collect(Collectors.toMap(Hotel::getId, Function.identity()));

        return mine.stream()
                .filter(m -> byId.containsKey(m.getHotelId()))
                .map(m -> new MyHotelResponse(HotelResponse.from(byId.get(m.getHotelId())), Set.copyOf(m.getRoles())))
                .sorted(Comparator.comparing(r -> r.hotel().name(), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public HotelResponse get(TenantContext tenant) {
        return HotelResponse.from(load(tenant));
    }

    public HotelResponse update(TenantContext tenant, UpdateHotelRequest request) {
        tenant.requireAnyRole(HotelRole.OWNER);
        Hotel hotel = load(tenant);

        if (request.name() != null) {
            String name = request.name().trim();
            if (name.isEmpty()) {
                throw new DomainException(ErrorCode.VALIDATION_FAILED, "Hotel name must not be blank");
            }
            hotel.setName(name); // the slug deliberately stays the same
        }
        if (request.address() != null) {
            hotel.setAddress(trimToNull(request.address()));
        }
        if (request.timezone() != null) {
            hotel.setTimezone(validZone(request.timezone()));
        }
        hotel.setUpdatedAt(clock.instant());
        return HotelResponse.from(hotels.save(hotel));
    }

    private Hotel load(TenantContext tenant) {
        return hotels.findById(tenant.hotelId()).orElseThrow(() -> DomainException.notFound("Hotel"));
    }

    private String uniqueSlug(String name) {
        String base = Slugs.slugify(name);
        if (!hotels.existsBySlug(base)) {
            return base;
        }
        for (int i = 0; i < 5; i++) {
            String candidate = base + "-" + ThreadLocalRandom.current().nextInt(1000, 10000);
            if (!hotels.existsBySlug(candidate)) {
                return candidate;
            }
        }
        throw DomainException.conflict("Could not pick a unique address for this hotel, please try again");
    }

    private static String validZone(String id) {
        try {
            return ZoneId.of(id.trim()).getId();
        } catch (DateTimeException e) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "Unknown timezone");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}