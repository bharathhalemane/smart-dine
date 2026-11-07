package com.example.qrorder.ordering.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.ordering.dto.KitchenOrderResponse;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.ordering.repository.OrderRepository;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.model.Permission;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

@Service
public class KitchenOrderService {
    private static final Set<OrderStatus> OPEN = EnumSet.of(OrderStatus.PENDING, OrderStatus.ACCEPTED, OrderStatus.PREPARING, OrderStatus.READY);

    private final OrderRepository orders;
    private final OrderTransitionService transitions;

    public KitchenOrderService(OrderRepository orders, OrderTransitionService transitions){
        this.orders = orders;
        this.transitions = transitions;
    }

    public List<KitchenOrderResponse> queue(TenantContext tenant, List<String> statusFilter) {
        if (Stream.of(Permission.KITCHEN_OPERATE, Permission.DELIVER_ORDERS).noneMatch(tenant::can)) {
            throw new DomainException(ErrorCode.FORBIDDEN, "You are not allowed to do that");
        }
        return orders.findFirst200ByHotelIdAndStatusInOrderByCreatedAtAscIdAsc(tenant.hotelId(), parse(statusFilter))
                .stream().map(KitchenOrderResponse::from).toList();
    }
}