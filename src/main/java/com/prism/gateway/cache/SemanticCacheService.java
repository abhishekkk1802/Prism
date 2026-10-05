package com.prism.gateway.cache;

import com.prism.gateway.dto.ChatCompletionRequest;
import com.prism.gateway.dto.ChatCompletionResponse;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Semantic response cache orchestration.
 *
 * Flow on lookup:
 *   1. Build a canonical prompt string from the request messages.
 *   2. Exact-match fast path via SHA-256 hash (no embedding call).
 *   3. Semantic search via embedding + pgvector cosine similarity.
 *
 * Time-sensitive prompts (e.g. "what time is it now") bypass the cache entirely.
 */
@Service
public class SemanticCacheService {

    private final SemanticCacheRepository repository;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;
    private final CacheabilityChecker cacheabilityChecker;

    public SemanticCacheService(
            SemanticCacheRepository repository,
            EmbeddingService embeddingService,
            ObjectMapper objectMapper,
            CacheabilityChecker cacheabilityChecker
    ) {
        this.repository = repository;
        this.embeddingService = embeddingService;
        this.objectMapper = objectMapper;
        this.cacheabilityChecker = cacheabilityChecker;
    }

    /**
     * Attempts to serve the request from cache.
     * Returns empty on miss, on a time-sensitive prompt, or on any failure
     * (callers must fall through to the provider).
     */
    public Optional<CacheHit> lookup(
            UUID keyId,
            String model,
            ChatCompletionRequest request,
            double threshold
    ) {
        try {
            String prompt = canonicalPrompt(request);

            if (!cacheabilityChecker.isCacheable(prompt)) {
                return Optional.empty();
            }

            String hash = sha256(keyId, model, prompt);

            // 1. Exact-match fast path (no embedding call)
            Optional<SemanticCacheRepository.CacheEntry> exact =
                    repository.findByHash(keyId, model, hash);
            if (exact.isPresent()) {
                return toHit(exact.get());
            }

            // 2. Semantic search
            float[] embedding = embeddingService.embed(prompt);
            Optional<SemanticCacheRepository.CacheEntry> similar =
                    repository.findSimilar(keyId, model, embedding, threshold);
            if (similar.isPresent()) {
                return toHit(similar.get());
            }

            return Optional.empty();

        } catch (Exception e) {
            // Cache must never break the request path.
            System.out.println("Semantic cache lookup failed, treating as miss: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Asynchronously stores a successful response. Fire-and-forget: never
     * blocks or fails the response path.
     */
    @Async
    public void store(
            UUID keyId,
            String model,
            ChatCompletionRequest request,
            ChatCompletionResponse response,
            long inputTokens,
            long outputTokens
    ) {
        try {
            String prompt = canonicalPrompt(request);

            if (!cacheabilityChecker.isCacheable(prompt)) {
                return;
            }

            String hash = sha256(keyId, model, prompt);
            float[] embedding = embeddingService.embed(prompt);
            String responseJson = objectMapper.writeValueAsString(response);

            repository.save(
                    keyId, model, hash, prompt,
                    embedding, responseJson,
                    inputTokens, outputTokens,
                    null  // no expiry by default; time-sensitivity handled at prompt level
            );
        } catch (Exception e) {
            System.out.println("Semantic cache store failed: " + e.getMessage());
        }
    }

    private Optional<CacheHit> toHit(SemanticCacheRepository.CacheEntry entry) {
        try {
            ChatCompletionResponse response =
                    objectMapper.readValue(entry.response(), ChatCompletionResponse.class);
            repository.incrementHitCount(entry.id());
            return Optional.of(new CacheHit(
                    response,
                    entry.inputTokens(),
                    entry.outputTokens(),
                    entry.similarity()
            ));
        } catch (Exception e) {
            System.out.println("Failed to deserialize cached response: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Builds a stable prompt string from the request messages. */
    private String canonicalPrompt(ChatCompletionRequest request) {
        StringBuilder sb = new StringBuilder();
        for (ChatCompletionRequest.Message m : request.messages()) {
            sb.append(m.role().toLowerCase(Locale.ROOT))
              .append(':')
              .append(m.content().strip())
              .append('\n');
        }
        return sb.toString();
    }

    private String sha256(UUID keyId, String model, String prompt) {
        try {
            String material = keyId + "|" + model + "|" + prompt;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Aggregated cache statistics for a single API key. */
    public CacheStats stats(UUID keyId) {
        long[] hm = repository.hitsAndMisses(keyId);
        long hits = hm[0];
        long misses = hm[1];
        long total = hits + misses;
        double hitRate = total == 0 ? 0.0d : (double) hits / total;
        long entries = repository.countEntries(keyId);
        return new CacheStats(hits, misses, hitRate, entries);
    }

    public record CacheHit(
            ChatCompletionResponse response,
            long inputTokens,
            long outputTokens,
            double similarity
    ) {}

    public record CacheStats(
            long hitCount,
            long missCount,
            double hitRate,
            long entryCount
    ) {}
}
