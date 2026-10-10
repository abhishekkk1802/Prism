package com.prism.gateway.ratelimit;

import reactor.core.publisher.Mono;

public interface RateLimiter {

    Mono<RateLimitResult> check(
            String keyId,
            int limit
    );
}