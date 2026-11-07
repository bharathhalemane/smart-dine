package com.example.qrorder.ordering.controller;

import com.example.qrorder.ordering.dto.KitchenOrderResponse;
import com.example.qrorder.ordering.dto.ReportUnavailableRequest;
import com.example.qrorder.ordering.service.OrderLineService;
import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels/{hotelId}/kitchen/orders/{orderId}/lines")
public class KitchenOrderLineController {

    private final OrderLineService service;

    public KitchenOrderLineController(OrderLineService service) {
        this.service = service;
    }

    @PostMapping("/{lineNo}/unavailable")
    public KitchenOrderResponse reportUnavailable(@CurrentTenant TenantContext tenant,
                                                  @PathVariable("orderId") String orderId,
                                                  @PathVariable("lineNo") String lineNo,
                                                  @Valid @RequestBody(required = false) ReportUnavailableRequest body) {
        return service.reportUnavailable(tenant, orderId, lineNo,
                body == null ? null : body.reason(), body == null ? null : body.alsoMarkMenuItem());
    }
}