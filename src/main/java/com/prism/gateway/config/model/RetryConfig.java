package com.prism.gateway.config.model;

public record RetryConfig(
        int max_attempts,
        long initial_backoff_ms,
        double backoff_multiplier
) {
}