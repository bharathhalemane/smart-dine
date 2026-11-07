package com.example.qrorder.ordering.service;

public record OrderActor(String type, String id){

    public static final String CUSTOMER = "CUSTOMER";
    public static final String STAFF = "STAFF";

    public static  OrderActor customer(String customerSessionId){
        return new OrderActor(CUSTOMER, customerSessionId);
    }

    public static OrderActor staff(String userId){
        return new OrderActor(STAFF, userId);
    }
}