package com.example.qrorder.tables.dto;

import java.time.Instant;

/** The only response that ever contains the plain token. It cannot be retrieved again. */
public record IssuedQrResponse(String credentialId, String tableId, String token, String scanUrl,String qrImage, Instant issuedAt) {
}