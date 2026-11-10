package com.example.qrorder.realtime;

import com.example.qrorder.dining.service.CustomerCookies;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RealtimeController {

    private final ChannelDirectoryService directory;

    public RealtimeController(ChannelDirectoryService directory) {
        this.directory = directory;
    }

    /** Staff: the channels their permissions allow, and where to reload each one's state. */
    @GetMapping("/api/v1/hotels/{hotelId}/realtime/channels")
    public ChannelDirectory staff(@CurrentTenant TenantContext tenant) {
        return directory.forStaff(tenant);
    }

    /** Guests: their table's channel, and where to reload its state. */
    @GetMapping("/api/v1/customer/realtime")
    public ChannelDirectory guest(@CookieValue(name = CustomerCookies.NAME, required = false) String cookie) {
        return directory.forGuest(cookie);
    }
}