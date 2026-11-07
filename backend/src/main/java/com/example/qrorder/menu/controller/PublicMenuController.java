package com.example.qrorder.menu.controller;

import com.example.qrorder.menu.dto.PublicMenuResponse;
import com.example.qrorder.menu.service.PublicMenuService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public on purpose: customers read the menu before they have an account. The path variable is {id}, not {hotelId}. */
@RestController
@RequestMapping("/api/v1/public/hotels/{id}/menu")
public class PublicMenuController {

    private final PublicMenuService service;

    public PublicMenuController(PublicMenuService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<PublicMenuResponse> menu(@PathVariable("id") String hotelId) {
        // no-cache: availability changes minute to minute, so browsers must revalidate
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(service.menuFor(hotelId));
    }
}