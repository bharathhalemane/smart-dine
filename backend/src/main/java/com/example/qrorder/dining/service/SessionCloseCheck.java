package com.example.qrorder.dining.service;

import com.example.qrorder.dining.model.DiningSession;
import com.example.qrorder.tenancy.context.TenantContext;


public interface SessionCloseCheck {

    void check(TenantContext tenant, DiningSession session);
}