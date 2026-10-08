package com.prism.gateway.concurrency;

import com.prism.gateway.ratelimit.RateLimitResult;
import com.prism.gateway.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 11 — rate limiter admission contract under concurrency.
 *
 * The production FixedWindowRateLimiter's correctness rests entirely on the
 * atomicity of Redis INCR: each request gets a unique monotonically-increasing
 * count, and admission is {@code count <= limit}. This test models that exact
 * contract with an atomic-counter-backed fake limiter (no Redis needed) and
 * proves that under heavy concurrency:
 *   - admitted requests never exceed the configured limit
 *   - exactly min(concurrency, limit) are admitted within a single window
 *
 * This is deterministic and infra-free; the Redis integration itself is
 * validated by the live-stack load test (see PHASE 11 report).
 */
class RateLimiterConcurrencyTest {

    /**
     * Mirrors FixedWindowRateLimiter semantics using an AtomicLong per
     * (key, window-minute): INCR then allowed = count <= limit.
     */
    static final class AtomicFixedWindowLimiter implements RateLimiter {
        private final Map<String, AtomicLong> windows = new ConcurrentHashMap<>();
        private final long window;

        AtomicFixedWindowLimiter(long window) {
            this.window = window;
        }

        @Override
        public Mono<RateLimitResult> check(String keyId, int limit) {
            if (limit <= 0) {
                return Mono.just(new RateLimitResult(true, 0, limit));
            }
            long count = windows
                    .computeIfAbsent(keyId + ":" + window, k -> new AtomicLong())
                    .incrementAndGet();
            return Mono.just(new RateLimitResult(count <= limit, count, limit));
        }
    }

    @Test
    void concurrentChecksNeverExceedLimit() throws Exception {
        final int limit = 10;
        final int concurrency = 50;
        RateLimiter limiter = new AtomicFixedWindowLimiter(123L);

        AtomicInteger admitted = new AtomicInteger();
        CyclicBarrier barrier = new CyclicBarrier(concurrency);
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);

        try {
            List<java.util.concurrent.CompletableFuture<Void>> futures =
                    java.util.stream.IntStream.range(0, concurrency)
                            .mapToObj(i -> java.util.concurrent.CompletableFuture.runAsync(() -> {
                                try {
                                    barrier.await();
                                    RateLimitResult r = limiter.check("key-A", limit).block();
                                    if (r != null && r.allowed()) {
                                        admitted.incrementAndGet();
                                    }
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }, pool))
                            .toList();
            java.util.concurrent.CompletableFuture
                    .allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // Core safety property: never over-admit.
        assertTrue(admitted.get() <= limit,
                "admitted (" + admitted.get() + ") must be <= limit (" + limit + ")");
        // With a single window and concurrency > limit, exactly `limit` admitted.
        assertEquals(limit, admitted.get(),
                "exactly min(concurrency, limit) should be admitted in one window");
    }

    @Test
    void limitZeroOrNegativeIsUnlimited() {
        RateLimiter limiter = new AtomicFixedWindowLimiter(1L);
        RateLimitResult r = limiter.check("k", 0).block();
        assertTrue(r != null && r.allowed(), "limit<=0 means unlimited");
    }

    @Test
    void separateKeysDoNotShareCounters() {
        final int limit = 2;
        RateLimiter limiter = new AtomicFixedWindowLimiter(9L);

        // key-A exhausts its limit
        assertTrue(limiter.check("key-A", limit).block().allowed());
        assertTrue(limiter.check("key-A", limit).block().allowed());
        assertTrue(!limiter.check("key-A", limit).block().allowed(),
                "3rd call for key-A should be rejected");

        // key-B is unaffected (isolation)
        assertTrue(limiter.check("key-B", limit).block().allowed(),
                "key-B must have its own independent counter");
    }
}
