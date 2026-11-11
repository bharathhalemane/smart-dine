package com.example.qrorder.media.dto;

import jakarta.validation.constraints.NotBlank;

public record AttachImageRequest(@NotBlank String assetId) {
}