package com.example.qrorder.media.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** The few things the platform needs from an S3-compatible bucket. */
public interface ObjectStorage {

    record PresignedUpload(String url, Map<String, String> headers, Instant expiresAt) {}

    record ObjectInfo(long size, String contentType) {}

    /** A URL that accepts ONE PUT of exactly this content type and size, until it expires. */
    PresignedUpload presignUpload(String key, String contentType, long sizeBytes, Duration ttl);

    Optional<ObjectInfo> head(String key);

    byte[] readFirstBytes(String key, int count);

    void delete(String key);
}