package com.example.qrorder.media.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("mediaAssets")
@CompoundIndexes({
        @CompoundIndex(name = "mediaAssets_objectKey_unique", def = "{'objectKey': 1}", unique = true),
        @CompoundIndex(name = "mediaAssets_status_created", def = "{'status': 1, 'createdAt': 1}"),
        @CompoundIndex(name = "mediaAssets_hotel_status", def = "{'hotelId': 1, 'status': 1}")
})
public class MediaAsset {

    @Id
    private String id;

    private String hotelId;
    private MediaPurpose purpose;
    private String objectKey;
    private String contentType;
    private long sizeBytes;
    private MediaStatus status = MediaStatus.PENDING;
    /** Who uses this image once attached (e.g. "menuItem:<id>"). Null while unused. */
    private String claimedBy;
    private String createdBy;
    private Instant createdAt;

    protected MediaAsset() {}

    public MediaAsset(String hotelId, MediaPurpose purpose, String objectKey, String contentType, long sizeBytes,
                      String createdBy, Instant now) {
        this.hotelId = hotelId;
        this.purpose = purpose;
        this.objectKey = objectKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    public String getId() { return id; }
    public String getHotelId() { return hotelId; }
    public MediaPurpose getPurpose() { return purpose; }
    public String getObjectKey() { return objectKey; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public MediaStatus getStatus() { return status; }
    public String getClaimedBy() { return claimedBy; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}