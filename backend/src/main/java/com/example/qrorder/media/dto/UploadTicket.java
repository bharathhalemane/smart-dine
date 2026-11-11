package com.example.qrorder.media.dto;

import java.time.Instant;
import java.util.Map;

public record UploadTicket(String assetId, long maxBytes, Upload upload) {

    /** Send the file with this method, URL and these headers, exactly. */
    public record Upload(String method, String url, Map<String, String> headers, Instant expiresAt) {}
}