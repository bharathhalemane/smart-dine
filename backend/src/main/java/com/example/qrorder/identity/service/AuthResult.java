package com.example.qrorder.identity.service;

import com.example.qrorder.identity.dto.LoginResponse;

/** Response body for the client plus the refresh token the controller puts in the cookie. */
public record AuthResult(LoginResponse response, String refreshToken) {
}