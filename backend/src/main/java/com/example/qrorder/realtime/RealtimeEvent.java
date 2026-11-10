package com.example.qrorder.realtime;

import com.example.qrorder.billing.event.BillChanged;
import com.example.qrorder.dining.event.SessionChanged;
import com.example.qrorder.ordering.event.OrderChanged;
import com.example.qrorder.tables.event.FloorChanged;
import java.time.Instant;

/**
 * What goes over the wire. A hint with ids and a status, never prices or order lines:
 * the screen reloads the real data over REST.
 */
public record RealtimeEvent(String type, String hotelId, String sessionId, String tableNumber, String orderId,
                            String status, String from, Long version, String detail, String at) {

    public static RealtimeEvent of(OrderChanged e, Instant at) {
        return new RealtimeEvent(e.placed() ? "ORDER_PLACED" : "ORDER_CHANGED", e.hotelId(), e.diningSessionId(),
                e.tableNumber(), e.orderId(), e.to().name(), e.from() == null ? null : e.from().name(),
                e.version(), null, at.toString());
    }

    public static RealtimeEvent of(BillChanged e, Instant at) {
        return new RealtimeEvent("BILL_CHANGED", e.hotelId(), e.diningSessionId(), null, null, null, null, null,
                e.reason(), at.toString());
    }

    public static RealtimeEvent of(SessionChanged e, Instant at) {
        return new RealtimeEvent("SESSION_CHANGED", e.hotelId(), e.diningSessionId(), e.tableNumber(), null,
                e.status(), null, null, null, at.toString());
    }

    public static RealtimeEvent of(FloorChanged e, Instant at) {
        return new RealtimeEvent("FLOOR_CHANGED", e.hotelId(), null, e.tableNumber(), e.orderId(), null, null, null,
                e.kind(), at.toString());
    }
}