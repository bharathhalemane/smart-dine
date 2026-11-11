package com.example.qrorder.media.repository;

import com.example.qrorder.media.model.MediaAsset;
import com.example.qrorder.media.model.MediaStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MediaAssetRepository extends MongoRepository<MediaAsset, String> {

    Optional<MediaAsset> findByIdAndHotelId(String id, String hotelId);

    long countByHotelIdAndStatus(String hotelId, MediaStatus status);

    List<MediaAsset> findByStatusAndCreatedAtBefore(MediaStatus status, Instant before);
}