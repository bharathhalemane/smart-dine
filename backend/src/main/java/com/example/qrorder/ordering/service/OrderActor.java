package com.example.qrorder.ordering.service;

public record OrderActor(String type, String id){

    public static final String CUSTOMER = "CUSTOMER";
    public static final String STAFF = "STAFF";
    public static final String SYSTEM = "SYSTEM";

    public static  OrderActor customer(String customerSessionId){
        return new OrderActor(CUSTOMER, customerSessionId);
    }

    public static OrderActor staff(String userId){
        return new OrderActor(STAFF, userId);
    }
    public static OrderActor system(String source){
        return new OrderActor(SYSTEM, source);
    }
}