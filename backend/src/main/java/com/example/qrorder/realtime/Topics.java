package com.example.qrorder.realtime;

/** The names of the channels. 11.3 publishes to these; 11.2 decides who may listen. */
public final class Topics {

    private Topics() {}

    public static String kitchen(String hotelId) {
        return "/topic/hotels/" + hotelId + "/kitchen";
    }

    public static String waiters(String hotelId) {
        return "/topic/hotels/" + hotelId + "/waiters";
    }

    public static String billing(String hotelId) {
        return "/topic/hotels/" + hotelId + "/billing";
    }

    /** Everything the guests of one table session should hear. */
    public static String table(String diningSessionId) {
        return "/topic/tables/" + diningSessionId;
    }
}