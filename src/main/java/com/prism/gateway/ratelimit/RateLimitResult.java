package com.prism.gateway.ratelimit;

public record RateLimitResult(
        boolean allowed,
        long currentCount,
        long limit
) {
}