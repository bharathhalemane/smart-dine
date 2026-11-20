package com.example.qrorder.earnings.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.earnings")
public record EarningsProperties (
    @DefaultValue("0") int defaultCommissionBps,
    @DefaultValue("0") int fallbackFeeBps){
}
    
    