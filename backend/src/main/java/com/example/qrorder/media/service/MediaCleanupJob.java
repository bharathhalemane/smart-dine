package com.example.qrorder.media.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MediaCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(MediaCleanupJob.class);

    private final MediaService media;

    public MediaCleanupJob(MediaService media) {
        this.media = media;
    }

    @Scheduled(fixedDelayString = "${app.media.cleanup-interval-ms:600000}",
            initialDelayString = "${app.media.cleanup-initial-delay-ms:120000}")
    public void run() {
        try {
            int expired = media.expireStale();
            if (expired > 0) {
                log.info("Expired {} unfinished image uploads", expired);
            }
        } catch (RuntimeException e) {
            log.warn("Image cleanup failed: {}", e.getClass().getSimpleName());
        }
    }
}