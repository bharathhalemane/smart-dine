package com.example.qrorder.media.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** app.media.*  Storage is switched on by setting app.media.bucket. */
@ConfigurationProperties(prefix = "app.media")
public record StorageProperties(
        String endpoint,                                    // R2 / MinIO URL; empty for AWS S3
        @DefaultValue("us-east-1") String region,           // R2 uses "auto"
        String bucket,
        String accessKey,
        String secretKey,
        String publicBaseUrl,                               // where browsers read images (CDN / public bucket URL)
        @DefaultValue("false") boolean pathStyle,           // true for MinIO
        @DefaultValue("5242880") long maxBytes,             // 5 MB
        @DefaultValue("300") long uploadTtlSeconds,
        @DefaultValue("60") long pendingExpiryMinutes) {
}