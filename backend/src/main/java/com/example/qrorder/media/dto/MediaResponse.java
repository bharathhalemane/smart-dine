package com.example.qrorder.media.dto;

import com.example.qrorder.media.model.MediaStatus;

public record MediaResponse(String assetId, MediaStatus status, String url) {
}