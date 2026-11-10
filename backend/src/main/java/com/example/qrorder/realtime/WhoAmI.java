package com.example.qrorder.realtime;

public record WhoAmI(String kind, String id, String hotelId, String tableNumber) {

    public static WhoAmI of(RealtimePrincipal principal) {
        return new WhoAmI(principal.kind().name(), principal.id(), principal.hotelId(), principal.tableNumber());
    }
}