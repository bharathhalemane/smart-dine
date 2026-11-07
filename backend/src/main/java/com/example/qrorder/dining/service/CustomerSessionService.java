package com.example.qrorder.dining.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.dining.dto.CustomerMeResponse;
import com.example.qrorder.dining.model.CustomerSession;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.repository.CustomerSessionRepository;
import com.example.qrorder.dining.repository.DiningSessionRepository;
import com.example.qrorder.tenancy.dto.PublicHotelInfo;
import com.example.qrorder.tenancy.service.HotelLookupService;
import org.springframework.stereotype.Service;

@Service
public class CustomerSessionService {

    private final CustomerSessionRepository customers;
    private final DiningSessionRepository sessions;
    private final HotelLookupService hotels;

    public CustomerSessionService(CustomerSessionRepository customers, DiningSessionRepository sessions,
                                  HotelLookupService hotels) {
        this.customers = customers;
        this.sessions = sessions;
        this.hotels = hotels;
    }

    /** Turns a cookie value into a verified identity, or throws 401. */
    public CustomerContext resolve(String cookieToken) {
        if (cookieToken == null || cookieToken.isBlank()) {
            throw unauthenticated();
        }
        CustomerSession customer = customers.findByTokenHash(Tokens.sha256(cookieToken))
                .orElseThrow(CustomerSessionService::unauthenticated);
        DiningSession session = sessions.findById(customer.getDiningSessionId())
                .filter(DiningSession::isActive)
                .orElseThrow(() -> new DomainException(ErrorCode.SESSION_ENDED,
                        "This table session has ended. Scan the QR code again"));
        hotels.findActive(customer.getHotelId()).orElseThrow(() -> new DomainException(ErrorCode.SESSION_ENDED, "This restaurant is not available"));
        return new CustomerContext(customer.getId(), customer.getHotelId(), customer.getTableId(),
                session.getTableNumber(), session.getId(), session.getStatus(), customer.getNumber(), customer.getDisplayName());
    }

    public CustomerMeResponse me(String cookieToken) {
        return respond(resolve(cookieToken));
    }

    public CustomerMeResponse updateName(String cookieToken, String rawName) {
        CustomerContext context = resolve(cookieToken);
        String name = CustomerNames.clean(rawName);
        CustomerSession customer = customers.findById(context.customerSessionId())
                .orElseThrow(CustomerSessionService::unauthenticated);
        customer.setDisplayName(name);
        customers.save(customer);
        return respond(resolve(cookieToken));
    }

    private CustomerMeResponse respond(CustomerContext context) {
        PublicHotelInfo hotel = hotels.findActive(context.hotelId())
                .orElseThrow(() -> new DomainException(ErrorCode.SESSION_ENDED, "This restaurant is not available"));
        return new CustomerMeResponse(hotel.name(), context.tableNumber(), context.sessionStatus(),
                context.number(), context.displayName(), context.label());
    }

    private static DomainException unauthenticated() {
        return new DomainException(ErrorCode.UNAUTHENTICATED, "Scan the QR code on your table to continue");
    }
}