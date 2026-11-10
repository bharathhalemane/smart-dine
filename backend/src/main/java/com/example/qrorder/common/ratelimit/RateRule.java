package com.example.qrorder.common.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.function.Function;
import java.util.function.Predicate;

/** One limit: which requests it covers, who they are counted as, and how many are allowed per window. */
public record RateRule(String name, Predicate<HttpServletRequest> applies, int limit, Duration window,
                       Function<HttpServletRequest, String> key) {
}