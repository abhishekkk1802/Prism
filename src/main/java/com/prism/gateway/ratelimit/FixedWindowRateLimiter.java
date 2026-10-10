package com.prism.gateway.ratelimit;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

@Component
public class FixedWindowRateLimiter implements RateLimiter {

    private final ReactiveStringRedisTemplate redisTemplate;

    private final DefaultRedisScript<Long> rateLimitScript;

    public FixedWindowRateLimiter(
            ReactiveStringRedisTemplate redisTemplate
    ) {
        this.redisTemplate = redisTemplate;

        this.rateLimitScript = new DefaultRedisScript<>();

        this.rateLimitScript.setScriptText("""
                local current = redis.call('INCR', KEYS[1])

                if current == 1 then
                    redis.call('EXPIRE', KEYS[1], 60)
                end

                return current
                """);

        this.rateLimitScript.setResultType(Long.class);
    }

    @Override
    public Mono<RateLimitResult> check(
            String keyId,
            int limit
    ) {

        if (limit <= 0) {
            return Mono.just(
                    new RateLimitResult(
                            true,
                            0,
                            limit
                    )
            );
        }

        long currentMinute =
                Instant.now().getEpochSecond() / 60;

        String redisKey =
                "rpm:" + keyId + ":" + currentMinute;

        return redisTemplate
                .execute(
                        rateLimitScript,
                        List.of(redisKey),
                        List.of()
                )
                .next()
                .map(currentCount ->
                        new RateLimitResult(
                                currentCount <= limit,
                                currentCount,
                                limit
                        )
                );
    }
}