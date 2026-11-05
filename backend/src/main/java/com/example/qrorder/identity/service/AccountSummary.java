package com.example.qrorder.identity.service;

/** What other modules may know about a user: no hash, no status internals. */
public record AccountSummary(String id, String name, String email) {
}