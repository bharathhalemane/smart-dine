package com.example.qrorder.support;

import com.example.qrorder.media.storage.ObjectStorage;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory bucket for tests. put() plays the part of the browser's upload. */
public class FakeObjectStorage implements ObjectStorage {

    private record Stored(byte[] bytes, String contentType) {}

    private final Map<String, Stored> objects = new ConcurrentHashMap<>();

    public void put(String key, String contentType, byte[] bytes) {
        objects.put(key, new Stored(bytes, contentType));
    }

    public boolean exists(String key) {
        return objects.containsKey(key);
    }

    public void clear() {
        objects.clear();
    }

    @Override
    public PresignedUpload presignUpload(String key, String contentType, long sizeBytes, Duration ttl) {
        return new PresignedUpload("https://fake-storage.test/" + key, Map.of("Content-Type", contentType),
                Instant.now().plus(ttl));
    }

    @Override
    public Optional<ObjectInfo> head(String key) {
        Stored stored = objects.get(key);
        return stored == null ? Optional.empty() : Optional.of(new ObjectInfo(stored.bytes().length, stored.contentType()));
    }

    @Override
    public byte[] readFirstBytes(String key, int count) {
        byte[] bytes = objects.get(key).bytes();
        return Arrays.copyOf(bytes, Math.min(count, bytes.length));
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
    }
}