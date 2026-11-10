package com.example.qrorder.admin.controller;

import com.example.qrorder.admin.dto.AdminHotelDetail;
import com.example.qrorder.admin.dto.DeactivateHotelRequest;
import com.example.qrorder.admin.dto.HotelActionRequest;
import com.example.qrorder.admin.service.HotelAdminService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Platform-admin only (SecurityConfig), and the service re-checks the role in the database. */
@RestController
@RequestMapping("/api/v1/admin/hotels/{id}")
public class AdminHotelActionController {

    private final HotelAdminService service;

    public AdminHotelActionController(HotelAdminService service) {
        this.service = service;
    }

    @PostMapping("/suspend")
    public AdminHotelDetail suspend(Authentication who, @PathVariable("id") String id,
                                    @Valid @RequestBody HotelActionRequest request) {
        return service.suspend(who.getName(), id, request.reason()); // the authentication's name is the user id
    }

    @PostMapping("/reactivate")
    public AdminHotelDetail reactivate(Authentication who, @PathVariable("id") String id,
                                       @Valid @RequestBody HotelActionRequest request) {
        return service.reactivate(who.getName(), id, request.reason());
    }

    @PostMapping("/deactivate")
    public AdminHotelDetail deactivate(Authentication who, @PathVariable("id") String id,
                                       @Valid @RequestBody DeactivateHotelRequest request) {
        return service.deactivate(who.getName(), id, request.reason(), request.confirmSlug());
    }
}