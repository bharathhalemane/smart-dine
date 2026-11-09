package com.example.qrorder.billing.model;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** How a hotel gets paid. A hotel with no document of its own accepts cash only. */
@Document("paymentSettings")
public class PaymentSettings {

    @Id
    private String id;

    @Indexed(unique = true)
    private String hotelId;

    private List<PaymentMethod> enabledMethods;
    private String upiId;
    private String payeeName;
    private boolean partialPaymentsAllowed;
    private String updatedByUserId;
    private PaymentTiming paymentTiming;
    private Instant updatedAt;

    protected PaymentSettings() {}

    public PaymentSettings(String hotelId, List<PaymentMethod> enabledMethods, String upiId, String payeeName,
                           boolean partialPaymentsAllowed, String updatedByUserId, Instant updatedAt) {
        this.hotelId = hotelId;
        this.enabledMethods = enabledMethods;
        this.upiId = upiId;
        this.payeeName = payeeName;
        this.partialPaymentsAllowed = partialPaymentsAllowed;
        this.updatedByUserId = updatedByUserId;
        this.updatedAt = updatedAt;
    }

    public static PaymentSettings defaults(String hotelId) {
        return new PaymentSettings(hotelId, List.of(PaymentMethod.CASH), null, null, true, null, null);
    }

    public String getHotelId() { return hotelId; }
    public String getUpiId() { return upiId; }
    public String getPayeeName() { return payeeName; }
    public boolean isPartialPaymentsAllowed() { return partialPaymentsAllowed; }
    public String getUpdatedByUserId() { return updatedByUserId; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** The enabled methods in a fixed order (the order of the enum), never empty. */
    public List<PaymentMethod> getEnabledMethods() {
        if (enabledMethods == null || enabledMethods.isEmpty()) {
            return List.of(PaymentMethod.CASH);
        }
        return List.copyOf(EnumSet.copyOf(enabledMethods));
    }
    /** When guests pay. Hotels with no choice recorded pay after the meal. */
    public PaymentTiming getPaymentTiming() {
        return paymentTiming == null ? PaymentTiming.AFTER_MEAL : paymentTiming;
    }

    public boolean isPayBeforeFood() {
        return getPaymentTiming() == PaymentTiming.BEFORE_FOOD;
    }

    public boolean accepts(PaymentMethod method) {
        return getEnabledMethods().contains(method);
    }
}