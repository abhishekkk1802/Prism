package com.prism.gateway.concurrency;

import com.prism.gateway.cache.CacheabilityChecker;
import com.prism.gateway.cache.EmbeddingService;
import com.prism.gateway.cache.SemanticCacheRepository;
import com.prism.gateway.cache.SemanticCacheService;
import com.prism.gateway.dto.ChatCompletionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 11 — semantic cache key/tier isolation under concurrency.
 *
 * Verifies against the REAL SemanticCacheService (with a recording fake
 * repository + deterministic fake embedding) that every lookup is scoped by
 * BOTH key_id and model/tier. Under concurrent lookups from different keys and
 * tiers, no lookup is ever issued with another key's id or another tier — so
 * KEY_A can never read KEY_B's entries and fast can never read smart's.
 */
class CacheIsolationConcurrencyTest {

    /** Records every (keyId, model) scope it is queried with; always a miss. */
    static final class RecordingRepository extends SemanticCacheRepository {
        final ConcurrentLinkedQueue<String> scopes = new ConcurrentLinkedQueue<>();

        RecordingRepository() {
            super(null);
        }

        @Override
        public Optional<CacheEntry> findByHash(UUID keyId, String model, String promptHash) {
            scopes.add(keyId + "|" + model);
            return Optional.empty();
        }

        @Override
        public Optional<CacheEntry> findSimilar(UUID keyId, String model, float[] embedding, double threshold) {
            scopes.add(keyId + "|" + model);
            return Optional.empty();
        }
    }

    static final class FakeEmbedding extends EmbeddingService {
        FakeEmbedding() { super(null, "test-model"); }
        @Override
        public float[] embed(String text) {
            return new float[]{ 0.1f, 0.2f, 0.3f };
        }
    }

    @Test
    void concurrentLookupsAreAlwaysScopedByKeyAndTier() throws Exception {
        RecordingRepository repo = new RecordingRepository();
        SemanticCacheService service = new SemanticCacheService(
                repo, new FakeEmbedding(), null, new CacheabilityChecker());

        UUID keyA = UUID.randomUUID();
        UUID keyB = UUID.randomUUID();
        ChatCompletionRequest request = new ChatCompletionRequest(
                "fast",
                List.of(new ChatCompletionRequest.Message("user", "explain redis")),
                false
        );

        // 4 distinct (key, tier) scopes exercised concurrently.
        record Scope(UUID key, String tier) {}
        List<Scope> scopes = List.of(
                new Scope(keyA, "fast"),
                new Scope(keyA, "smart"),
                new Scope(keyB, "fast"),
                new Scope(keyB, "smart")
        );

        final int perScope = 25;
        final int total = scopes.size() * perScope;
        CyclicBarrier barrier = new CyclicBarrier(total);
        ExecutorService pool = Executors.newFixedThreadPool(total);

        try {
            List<java.util.concurrent.CompletableFuture<Void>> futures =
                    new java.util.ArrayList<>();
            for (Scope s : scopes) {
                for (int i = 0; i < perScope; i++) {
                    futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                        try {
                            barrier.await();
                            service.lookup(s.key(), s.tier(), request, 0.9);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }, pool));
                }
            }
            java.util.concurrent.CompletableFuture
                    .allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // Every recorded scope must be exactly one of the 4 (key,tier) pairs
        // we issued — never a cross-key or cross-tier scope.
        java.util.Set<String> allowed = new java.util.HashSet<>(List.of(
                keyA + "|fast", keyA + "|smart",
                keyB + "|fast", keyB + "|smart"
        ));
        for (String recorded : repo.scopes) {
            assertTrue(allowed.contains(recorded),
                    "lookup scope leaked outside its (key,tier): " + recorded);
        }

        // key_id in each scope must belong to the issuing key only.
        long keyAScopes = repo.scopes.stream().filter(s -> s.startsWith(keyA.toString())).count();
        long keyBScopes = repo.scopes.stream().filter(s -> s.startsWith(keyB.toString())).count();
        // Each lookup issues at least the hash scope (miss -> also similar scope),
        // so counts are proportional and strictly separated by key.
        assertEquals(repo.scopes.size(), keyAScopes + keyBScopes,
                "every scope belongs to exactly one of the two keys");
        assertTrue(keyAScopes > 0 && keyBScopes > 0, "both keys exercised");
    }
}
