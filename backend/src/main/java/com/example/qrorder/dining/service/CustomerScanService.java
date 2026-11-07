package com.example.qrorder.dining.service;

import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.dining.dto.ScanResponse;
import com.example.qrorder.dining.model.CustomerSession;
import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.dining.repository.CustomerSessionRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class CustomerScanService {

    /** newCustomerToken is the plaintext cookie value, or null when the caller already had a valid identity. */
    public record Outcome(ScanResponse response, String newCustomerToken) {}

    private final DiningSessionService diningSessions;
    private final CustomerSessionRepository customers;
    private final Clock clock;
    private final CustomerNumbering numbering;

    public CustomerScanService(DiningSessionService diningSessions, CustomerSessionRepository customers,
            CustomerNumbering numbering,Clock clock) {
        this.diningSessions = diningSessions;
        this.customers = customers;
        this.numbering = numbering;
        this.clock = clock;
    }

    public Outcome scan(String qrToken, String cookieToken) {
        DiningSessionService.ScannedTable scanned = diningSessions.scanTable(qrToken);
        DiningSession session = scanned.session();

        // Same device, same table visit: keep the identity it already has.
        if (cookieToken != null && !cookieToken.isBlank()) {
            boolean alreadyHere = customers.findByTokenHash(Tokens.sha256(cookieToken))
                    .filter(c -> c.getDiningSessionId().equals(session.getId()))
                    .isPresent();
            if (alreadyHere) {
                return new Outcome(scanned.response(), null);
            }
        }

        String token = Tokens.newToken();
        int number = numbering.next(session.getId());
        customers.save(new CustomerSession(session.getHotelId(), session.getTableId(), session.getId(),
                Tokens.sha256(token), number, clock.instant()));
        return new Outcome(scanned.response(), token);
    }
}