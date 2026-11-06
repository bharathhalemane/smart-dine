package com.example.qrorder.tables.dto;

import java.time.Instant;

public record QrStatusResponse(boolean active, String credentialId, Instant issuedAt) {
}