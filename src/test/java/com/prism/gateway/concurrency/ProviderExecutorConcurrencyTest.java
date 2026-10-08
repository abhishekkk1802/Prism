package com.prism.gateway.concurrency;

import com.prism.gateway.config.model.GatewayConfig;
import com.prism.gateway.config.model.ModelAliasConfig;
import com.prism.gateway.config.model.ProviderConfig;
import com.prism.gateway.config.model.RetryConfig;
import com.prism.gateway.dto.ChatCompletionRequest;
import com.prism.gateway.dto.ChatCompletionResponse;
import com.prism.gateway.routing.ProviderRegistry;
import com.prism.gateway.routing.ProviderResolver;
import com.prism.gateway.service.CostCalculator;
import com.prism.gateway.service.LLMProvider;
import com.prism.gateway.service.ProviderExecutionResult;
import com.prism.gateway.service.ProviderExecutor;
import com.prism.gateway.service.ProviderRetryPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 11 — concurrent retry/fallback accounting against the REAL
 * ProviderExecutor with a deterministic fake LLMProvider (no network).
 *
 * Provider "alpha-*" always fails; provider "beta-*" always succeeds. Under
 * concurrency every request must: fall back to beta, report fallback=true,
 * record the primary's retries, and produce exactly one successful result
 * (so cost/usage is settled exactly once by the caller).
 */
class ProviderExecutorConcurrencyTest {

    private static final int MAX_ATTEMPTS = 3;

    /** alpha-* -> always throws (retryable IO); beta-* -> succeeds. */
    static final class FakeProvider implements LLMProvider {
        final AtomicInteger alphaCalls = new AtomicInteger();
        final AtomicInteger betaCalls = new AtomicInteger();

        @Override
        public ChatCompletionResponse complete(ChatCompletionRequest request,
                                               ProviderConfig providerConfig,
                                               String resolvedModel) {
            if (resolvedModel.startsWith("alpha")) {
                alphaCalls.incrementAndGet();
                throw new java.io.UncheckedIOException(
                        new java.io.IOException("alpha down"));
            }
            betaCalls.incrementAndGet();
            return new ChatCompletionResponse(
                    "id", "chat.completion", 0L, resolvedModel,
                    List.of(new ChatCompletionResponse.Choice(
                            0,
                            new ChatCompletionResponse.Message("assistant", "ok"),
                            "stop")),
                    new ChatCompletionResponse.Usage(10, 20)
            );
        }

        @Override
        public reactor.core.publisher.Flux<String> stream(ChatCompletionRequest request,
                                                           ProviderConfig providerConfig,
                                                           String resolvedModel) {
            return reactor.core.publisher.Flux.empty();
        }
    }

    private ProviderExecutor newExecutor(FakeProvider provider) {
        List<ProviderConfig> providers = List.of(
                new ProviderConfig("alpha", "http://localhost/v1", "k"),
                new ProviderConfig("beta", "http://localhost/v1", "k")
        );
        GatewayConfig cfg = new GatewayConfig(
                providers,
                Map.of(),
                new RetryConfig(MAX_ATTEMPTS, 1, 1.0) // tiny backoff for test speed
        );
        ProviderRegistry registry = new ProviderRegistry(cfg);
        ProviderResolver resolver = new ProviderResolver();
        CostCalculator cost = new CostCalculator(null) {
            @Override
            public BigDecimal calculate(String model, long in, long out) {
                return new BigDecimal("0.000100");
            }
        };
        return new ProviderExecutor(provider, registry, resolver, cfg, cost, new ProviderRetryPolicy());
    }

    @Test
    void concurrentRequestsFallBackToBetaAndSettleOnce() throws Exception {
        FakeProvider provider = new FakeProvider();
        ProviderExecutor executor = newExecutor(provider);

        ModelAliasConfig fast = new ModelAliasConfig("alpha-small", List.of("beta-small"), null);
        ChatCompletionRequest request = new ChatCompletionRequest(
                "fast",
                List.of(new ChatCompletionRequest.Message("user", "hello")),
                false
        );

        final int concurrency = 20;
        CyclicBarrier barrier = new CyclicBarrier(concurrency);
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        ConcurrentLinkedQueue<ProviderExecutionResult> results = new ConcurrentLinkedQueue<>();

        try {
            List<CompletableFuture<Void>> futures = java.util.stream.IntStream.range(0, concurrency)
                    .mapToObj(i -> CompletableFuture.runAsync(() -> {
                        try {
                            barrier.await();
                            results.add(executor.execute(request, fast));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }, pool))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // Every request produced exactly one successful result served by beta.
        assertEquals(concurrency, results.size(), "each request yields exactly one result");
        for (ProviderExecutionResult r : results) {
            assertEquals("beta", r.provider(), "served by fallback provider beta");
            assertEquals("beta-small", r.model());
            assertTrue(r.fallback(), "fallback flag must be true");
            // primary alpha exhausted MAX_ATTEMPTS; beta succeeded on first try.
            assertEquals(MAX_ATTEMPTS, r.retries(),
                    "retries should account for the failed primary attempts");
            assertEquals(10, r.inputTokens());
            assertEquals(20, r.outputTokens());
        }

        // alpha was tried MAX_ATTEMPTS per request; beta exactly once per request
        // (success is not retried) -> no duplicate successful executions.
        assertEquals(concurrency * MAX_ATTEMPTS, provider.alphaCalls.get());
        assertEquals(concurrency, provider.betaCalls.get(),
                "beta called exactly once per request (settle-once invariant)");
    }
}
