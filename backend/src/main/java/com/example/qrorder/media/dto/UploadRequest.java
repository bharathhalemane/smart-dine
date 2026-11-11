package com.example.qrorder.media.dto;

import com.example.qrorder.media.model.MediaPurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record UploadRequest(@NotNull MediaPurpose purpose, @NotBlank String contentType, @Positive long sizeBytes) {
}