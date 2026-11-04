package com.example.qrorder.support;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only: lets integration tests prove the role gate works. Not part of the production code. */
@RestController
public class SecurityProbeController {

    @GetMapping("/api/v1/_probe/admin")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public String adminOnly() {
        return "ok";
    }
}