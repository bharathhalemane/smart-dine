package com.example.qrorder.media.service;

import com.example.qrorder.common.error.DomainException;
import com.example.qrorder.common.error.ErrorCode;
import com.example.qrorder.media.dto.MediaResponse;
import com.example.qrorder.media.dto.UploadRequest;
import com.example.qrorder.media.dto.UploadTicket;
import com.example.qrorder.media.model.MediaAsset;
import com.example.qrorder.media.model.MediaPurpose;
import com.example.qrorder.media.model.MediaStatus;
import com.example.qrorder.media.repository.MediaAssetRepository;
import com.example.qrorder.media.storage.ObjectStorage;
import com.example.qrorder.media.storage.ObjectStorage.ObjectInfo;
import com.example.qrorder.media.storage.ObjectStorage.PresignedUpload;
import com.example.qrorder.media.storage.StorageProperties;
import com.example.qrorder.tenancy.context.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);
    private static final Map<String, String> EXTENSIONS = Map.of("image/jpeg", "jpg", "image/png", "png",
            "image/webp", "webp");
    private static final long MAX_PENDING_PER_HOTEL = 100;

    public record ClaimedImage(String assetId, String url) {}

    private final MediaAssetRepository assets;
    private final MongoTemplate mongo;
    private final ObjectProvider<ObjectStorage> storageProvider;
    private final StorageProperties props;
    private final Clock clock;

    public MediaService(MediaAssetRepository assets, MongoTemplate mongo, ObjectProvider<ObjectStorage> storageProvider,
                        StorageProperties props, Clock clock) {
        this.assets = assets;
        this.mongo = mongo;
        this.storageProvider = storageProvider;
        this.props = props;
        this.clock = clock;
    }

    // ---------- step 1: ask for an upload ----------

    public UploadTicket requestUpload(TenantContext tenant, UploadRequest request) {
        tenant.require(request.purpose().permission());
        String contentType = request.contentType().trim().toLowerCase(Locale.ROOT);
        String extension = EXTENSIONS.get(contentType);
        if (extension == null) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "Only JPEG, PNG or WebP images are allowed");
        }
        if (request.sizeBytes() > props.maxBytes()) {
            throw new DomainException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "Images can be at most " + (props.maxBytes() / (1024 * 1024)) + " MB");
        }
        ObjectStorage storage = storage();
        if (assets.countByHotelIdAndStatus(tenant.hotelId(), MediaStatus.PENDING) >= MAX_PENDING_PER_HOTEL) {
            throw new DomainException(ErrorCode.RATE_LIMITED,
                    "Too many unfinished uploads; finish them or wait for them to expire");
        }

        String key = "hotels/%s/%s/%s.%s".formatted(tenant.hotelId(), request.purpose().folder(),
                UUID.randomUUID(), extension);
        MediaAsset asset = assets.save(new MediaAsset(tenant.hotelId(), request.purpose(), key, contentType,
                request.sizeBytes(), tenant.userId(), clock.instant()));
        PresignedUpload presigned = storage.presignUpload(key, contentType, request.sizeBytes(),
                Duration.ofSeconds(props.uploadTtlSeconds()));
        return new UploadTicket(asset.getId(), props.maxBytes(),
                new UploadTicket.Upload("PUT", presigned.url(), presigned.headers(), presigned.expiresAt()));
    }

    // ---------- step 3: confirm the file really arrived and really is an image ----------

    public MediaResponse complete(TenantContext tenant, String assetId) {
        MediaAsset asset = assets.findByIdAndHotelId(assetId, tenant.hotelId())
                .orElseThrow(() -> DomainException.notFound("Upload"));
        tenant.require(asset.getPurpose().permission());
        if (asset.getStatus() == MediaStatus.READY) {
            return respond(asset); // calling complete twice is fine
        }
        if (asset.getStatus() != MediaStatus.PENDING) {
            throw DomainException.conflict("This upload can no longer be completed");
        }

        ObjectStorage storage = storage();
        ObjectInfo info = storage.head(asset.getObjectKey())
                .orElseThrow(() -> DomainException.conflict("The file has not been uploaded yet"));
        if (info.size() != asset.getSizeBytes() || !asset.getContentType().equalsIgnoreCase(info.contentType())) {
            throw reject(asset, storage, "The uploaded file does not match what was declared");
        }
        if (!ImageSniffer.matches(asset.getContentType(),
                storage.readFirstBytes(asset.getObjectKey(), ImageSniffer.BYTES_NEEDED))) {
            throw reject(asset, storage, "The uploaded file is not a valid image of the declared type");
        }

        if (!transition(asset.getId(), MediaStatus.PENDING, MediaStatus.READY)) {
            MediaAsset current = assets.findById(asset.getId()).orElseThrow(() -> DomainException.notFound("Upload"));
            if (current.getStatus() == MediaStatus.READY) {
                return respond(current); // a parallel complete won
            }
            throw DomainException.conflict("This upload can no longer be completed");
        }
        return new MediaResponse(asset.getId(), MediaStatus.READY, publicUrl(asset.getObjectKey()));
    }

    // ---------- step 4: used by the menu module ----------

    /** Marks a READY image as used by `owner`. Same hotel and purpose only; one user per image. */
    public ClaimedImage claim(TenantContext tenant, String assetId, MediaPurpose purpose, String owner) {
        MediaAsset asset = assets.findByIdAndHotelId(assetId, tenant.hotelId())
                .filter(a -> a.getPurpose() == purpose)
                .orElseThrow(() -> DomainException.notFound("Image"));
        if (asset.getStatus() != MediaStatus.READY) {
            throw DomainException.conflict("This image has not been uploaded and confirmed yet");
        }
        long changed = mongo.updateFirst(Query.query(Criteria.where("id").is(assetId)
                        .and("status").is(MediaStatus.READY).and("claimedBy").is(null)),
                new Update().set("claimedBy", owner), MediaAsset.class).getModifiedCount();
        if (changed == 0) {
            MediaAsset current = assets.findById(assetId).orElseThrow(() -> DomainException.notFound("Image"));
            if (!owner.equals(current.getClaimedBy())) {
                throw DomainException.conflict("This image is already used elsewhere");
            }
        }
        return new ClaimedImage(asset.getId(), publicUrl(asset.getObjectKey()));
    }

    /** The image is no longer used: mark it deleted and remove the file (best effort). */
    public void discard(TenantContext tenant, String assetId) {
        assets.findByIdAndHotelId(assetId, tenant.hotelId()).ifPresent(asset -> {
            if (transition(asset.getId(), MediaStatus.READY, MediaStatus.DELETED)) {
                deleteQuietly(asset.getObjectKey());
            }
        });
    }

    // ---------- housekeeping ----------

    /** Uploads that were requested but never completed. Returns how many were expired. */
    public int expireStale() {
        var cutoff = clock.instant().minus(Duration.ofMinutes(props.pendingExpiryMinutes()));
        int expired = 0;
        for (MediaAsset asset : assets.findByStatusAndCreatedAtBefore(MediaStatus.PENDING, cutoff)) {
            if (transition(asset.getId(), MediaStatus.PENDING, MediaStatus.EXPIRED)) {
                deleteQuietly(asset.getObjectKey());
                expired++;
            }
        }
        return expired;
    }

    // ---------- helpers ----------

    private DomainException reject(MediaAsset asset, ObjectStorage storage, String reason) {
        if (transition(asset.getId(), MediaStatus.PENDING, MediaStatus.REJECTED)) {
            deleteQuietly(asset.getObjectKey());
        }
        return new DomainException(ErrorCode.VALIDATION_FAILED, reason);
    }

    private boolean transition(String id, MediaStatus from, MediaStatus to) {
        return mongo.updateFirst(Query.query(Criteria.where("id").is(id).and("status").is(from)),
                new Update().set("status", to), MediaAsset.class).getModifiedCount() == 1;
    }

    private void deleteQuietly(String key) {
        ObjectStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            return;
        }
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete stored file {}: {}", key, e.getClass().getSimpleName());
        }
    }

    private MediaResponse respond(MediaAsset asset) {
        return new MediaResponse(asset.getId(), asset.getStatus(), publicUrl(asset.getObjectKey()));
    }

    private String publicUrl(String key) {
        String base = props.publicBaseUrl();
        if (base == null || base.isBlank()) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Image storage is not configured");
        }
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/" + key;
    }

    private ObjectStorage storage() {
        return storageProvider.getIfAvailable(() -> {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Image storage is not configured");
        });
    }
}