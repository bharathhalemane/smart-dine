package com.example.qrorder.billing.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Builds the UPI payment link a phone can open (or a QR code can carry). Pure: no database, no clock. */
final class UpiLinks {

    private UpiLinks() {}

    /**
     * upi://pay?pa=&lt;id&gt;&amp;pn=&lt;name&gt;&amp;am=&lt;rupees&gt;&amp;cu=INR&amp;tn=&lt;note&gt;.
     * Null unless the bill is in INR and the hotel has given both a UPI id and a payee name.
     * A zero amount is left out, so the payer types it in.
     */
    static String build(String upiId, String payeeName, String currency, long amountMinor, String note) {
        if (!"INR".equals(currency) || upiId == null || upiId.isBlank() || payeeName == null || payeeName.isBlank()) {
            return null;
        }
        StringBuilder link = new StringBuilder("upi://pay?pa=").append(upiId).append("&pn=").append(encode(payeeName));
        if (amountMinor > 0) {
            link.append("&am=").append(rupees(amountMinor));
        }
        link.append("&cu=INR");
        if (note != null && !note.isBlank()) {
            link.append("&tn=").append(encode(note));
        }
        return link.toString();
    }

    /** 115503 paise becomes "1155.03". */
    static String rupees(long amountMinor) {
        return String.format("%d.%02d", amountMinor / 100, amountMinor % 100);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}