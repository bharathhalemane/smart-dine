package com.example.qrorder.tenancy.controller;

import com.example.qrorder.tenancy.context.CurrentTenant;
import com.example.qrorder.tenancy.context.TenantContext;
import com.example.qrorder.tenancy.dto.CreateHotelRequest;
import com.example.qrorder.tenancy.dto.HotelResponse;
import com.example.qrorder.tenancy.dto.MyHotelResponse;
import com.example.qrorder.tenancy.dto.UpdateHotelRequest;
import com.example.qrorder.tenancy.service.HotelService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hotels")
public class HotelController {

    private final HotelService hotelService;

    public HotelController(HotelService hotelService) {
        this.hotelService = hotelService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HotelResponse create(Authentication authentication, @Valid @RequestBody CreateHotelRequest request) {
        return hotelService.create(authentication.getName(), request);
    }

    @GetMapping
    public List<MyHotelResponse> myHotels(Authentication authentication) {
        return hotelService.myHotels(authentication.getName());
    }

    @GetMapping("/{hotelId}")
    public HotelResponse get(@CurrentTenant TenantContext tenant) {
        return hotelService.get(tenant);
    }

    @PatchMapping("/{hotelId}")
    public HotelResponse update(@CurrentTenant TenantContext tenant, @Valid @RequestBody UpdateHotelRequest request) {
        return hotelService.update(tenant, request);
    }
}