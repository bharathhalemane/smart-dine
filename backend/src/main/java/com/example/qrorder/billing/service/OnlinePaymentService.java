package com.example.qrorder.billing.service;

import com.example.qrorder.billing.dto.BillResponse;
import com.example.qrorder.billing.dto.OnlinePaymentStart;
import com.example.qrorder.billing.dto.OnlinePaymentView;
import com.example.qrorder.billing.dto.StartOnlinePaymentRequest;
import com.example.qrorder.billing.model.HotelPaymentProvider;
import com.example.qrorder.billing.model.OnlinePayment;
import com.example.qrorder.billing.model.PaymentMethod;
import com.example.qrorder.billing.model.PaymentSettings;
import com.example.qrorder.billing.provider.CreatedOrder;
import com.example.qrorder.billing.provider.PaymentProvider;
import com.example.qrorder.billing.provider.PaymentProviders;
import com.example.qrorder.billing.provider.ProviderException;
import com.example.qrorder.billing.repository.OnlinePaymentRepository;
import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.common.security.Tokens;
import com.example.qrorder.dining.service.CustomerContext;
import java.time.Clock;
import java.util.Optional;
import java.util.regex.Pattern;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** The diner's side of paying online: start an attempt, and look at how it went. */
@Service
public class OnlinePaymentService {

    private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9_-]{16,64}");
    static final long MINIMUM_MINOR = 100; // the provider's smallest payment is 1.00

    /** created is false when this call returned an attempt that an earlier identical request had started. */
    public record Started(OnlinePaymentStart body, boolean created) {}

    private final OnlinePaymentRepository attempts;
    private final BillService bills;
    private final PaymentSettingsService settings;
    private final ProviderConfigService providerConfigs;
    private final PaymentProviders providers;
    private final Clock clock;

    public OnlinePaymentService(OnlinePaymentRepository attempts, BillService bills,
                                PaymentSettingsService settings, ProviderConfigService providerConfigs,
                                PaymentProviders providers, Clock clock) {
        this.attempts = attempts;
        this.bills = bills;
        this.settings = settings;
        this.providerConfigs = providerConfigs;
        this.providers = providers;
        this.clock = clock;
    }

    public Started start(CustomerContext customer, String idempotencyKey, StartOnlinePaymentRequest request) {
        if (idempotencyKey == null || !KEY_FORMAT.matcher(idempotencyKey).matches()) {
            throw validation("Send an Idempotency-Key header of 16 to 64 characters: letters, digits, _ or -");
        }
        Long asked = request == null ? null : request.amountMinor();
        String requestHash = Tokens.sha256("start|" + (asked == null ? "full" : asked));

        Optional<OnlinePayment> existing = attempts.findByCustomerSessionIdAndIdempotencyKey(
                customer.customerSessionId(), idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }

        PaymentSettings accepted = settings.effective(customer.hotelId());
        HotelPaymentProvider config = providerConfigs.find(customer.hotelId()).orElse(null);
        if (!accepted.accepts(PaymentMethod.ONLINE) || config == null) {
            throw validation("This hotel does not take online payments");
        }

        BillResponse bill = bills.forCustomer(customer);
        long due = bill.balanceMinor();
        if (due == 0) {
            throw DomainException.conflict("Nothing is due on this table right now");
        }
        long amount = asked == null ? due : asked;
        if (amount > due) {
            throw DomainException.conflict("That is more than the amount due (" + money(due, bill.currency()) + ")");
        }
        if (amount < MINIMUM_MINOR) {
            throw validation("The smallest online payment is " + money(MINIMUM_MINOR, bill.currency()));
        }
        if (!accepted.isPartialPaymentsAllowed() && amount != due) {
            throw validation("This hotel only takes the full amount due (" + money(due, bill.currency())
                    + ") in one payment");
        }

        String attemptId = new ObjectId().toHexString(); // also our receipt number at the provider
        PaymentProvider provider = providers.get(config.getProvider());
        CreatedOrder order;
        try {
            order = provider.createOrder(providerConfigs.credentials(config), amount, bill.currency(), attemptId);
        } catch (ProviderException e) {
            throw DomainException.conflict("The payment provider is not available right now, please try again");
        }

        OnlinePayment attempt = new OnlinePayment(attemptId, customer.hotelId(), customer.diningSessionId(),
                customer.tableNumber(), customer.customerSessionId(), customer.label(), amount, bill.currency(),
                provider.id(), order.providerOrderId(), idempotencyKey, requestHash, clock.instant());
        try {
            attempts.insert(attempt);
        } catch (DuplicateKeyException race) {
            OnlinePayment winner = attempts.findByCustomerSessionIdAndIdempotencyKey(customer.customerSessionId(),
                    idempotencyKey).orElseThrow(() -> race);
            return replay(winner, requestHash);
        }
        return new Started(start(attempt, config.getKeyId()), true);
    }

    public OnlinePaymentView get(CustomerContext customer, String attemptId) {
        return attempts.findByIdAndDiningSessionId(attemptId, customer.diningSessionId())
                .map(OnlinePaymentView::from)
                .orElseThrow(() -> DomainException.notFound("Payment"));
    }

    private Started replay(OnlinePayment existing, String requestHash) {
        if (!requestHash.equals(existing.getRequestHash())) {
            throw DomainException.conflict("This Idempotency-Key was already used for a different payment");
        }
        String keyId = providerConfigs.find(existing.getHotelId()).map(HotelPaymentProvider::getKeyId).orElse(null);
        return new Started(start(existing, keyId), false);
    }

    private static OnlinePaymentStart start(OnlinePayment a, String keyId) {
        return new OnlinePaymentStart(a.getId(), a.getProvider(), a.getProviderOrderId(), keyId, a.getAmountMinor(),
                a.getCurrency(), a.getStatus());
    }

    private static String money(long minor, String currency) {
        return UpiLinks.rupees(minor) + " " + currency;
    }

    private static DomainException validation(String message) {
        return new DomainException(ErrorCode.VALIDATION_FAILED, message);
    }
}