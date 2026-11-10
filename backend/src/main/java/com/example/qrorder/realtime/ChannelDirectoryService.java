package com.example.qrorder.realtime;

import com.example.qrorder.dining.service.CustomerContext;
import com.example.qrorder.dining.service.CustomerSessionService;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** Tells a client which channels it may use, using the same permissions that guard the subscriptions. */
@Service
public class ChannelDirectoryService {

    private final CustomerSessionService customers;
    private final Clock clock;

    public ChannelDirectoryService(CustomerSessionService customers, Clock clock) {
        this.customers = customers;
        this.clock = clock;
    }

    public ChannelDirectory forStaff(TenantContext tenant) {
        String hotelId = tenant.hotelId();
        String base = "/api/v1/hotels/" + hotelId;
        List<ChannelInfo> channels = new ArrayList<>();
        if (tenant.can(Permission.KITCHEN_OPERATE)) {
            channels.add(new ChannelInfo("kitchen", Topics.kitchen(hotelId), List.of(base + "/kitchen/orders")));
        }
        if (tenant.can(Permission.DELIVER_ORDERS)) {
            channels.add(new ChannelInfo("waiters", Topics.waiters(hotelId), List.of(base + "/waiter/tasks")));
        }
        if (tenant.can(Permission.TAKE_PAYMENT)) {
            channels.add(new ChannelInfo("billing", Topics.billing(hotelId),
                    List.of(base + "/payments/waiting-orders")));
        }
        return new ChannelDirectory(clock.instant().toString(), channels);
    }

    public ChannelDirectory forGuest(String cookieToken) {
        CustomerContext guest = customers.resolve(cookieToken); // 401 if the cookie is missing or the table ended
        return new ChannelDirectory(clock.instant().toString(), List.of(new ChannelInfo("table",
                Topics.table(guest.diningSessionId()),
                List.of("/api/v1/customer/orders", "/api/v1/customer/bill"))));
    }
}