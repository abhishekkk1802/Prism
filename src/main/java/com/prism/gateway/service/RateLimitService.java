package com.prism.gateway.service;

import com.prism.gateway.ratelimit.RateLimitResult;
import com.prism.gateway.ratelimit.RateLimiter;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class RateLimitService {

    private final RateLimiter rateLimiter;

    public RateLimitService(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    public Mono<RateLimitResult> check(
            String keyId,
            int limit
    ) {
        return rateLimiter.check(keyId, limit);
    }
}