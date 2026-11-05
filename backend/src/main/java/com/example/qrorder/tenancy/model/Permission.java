package com.example.qrorder.tenancy.model;

/** What a person may do inside a hotel. Roles map to permissions; code checks permissions. */
public enum Permission {
    MANAGE_HOTEL,
    MANAGE_STAFF,
    MANAGE_MENU,
    TOGGLE_AVAILABILITY,
    MANAGE_TABLES,
    KITCHEN_OPERATE,
    DELIVER_ORDERS,
    TAKE_PAYMENT,
    ADJUST_BILL,
    CLEAR_TABLE
}