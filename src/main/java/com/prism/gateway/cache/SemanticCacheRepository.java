package com.prism.gateway.cache;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;

@Repository
public class SemanticCacheRepository {

    private final JdbcTemplate jdbcTemplate;

    public SemanticCacheRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Exact-match fast path: looks up a non-expired entry with an identical
     * prompt hash for this key + model. Avoids a vector scan when the same
     * prompt is sent verbatim.
     */
    public Optional<CacheEntry> findByHash(UUID keyId, String model, String promptHash) {

        String sql = """
                SELECT id, response, input_tokens, output_tokens
                FROM prism.semantic_cache
                WHERE key_id = ?
                  AND model = ?
                  AND prompt_hash = ?
                  AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                LIMIT 1
                """;

        return jdbcTemplate.query(sql, rs -> {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(new CacheEntry(
                    rs.getObject("id", UUID.class),
                    rs.getString("response"),
                    rs.getLong("input_tokens"),
                    rs.getLong("output_tokens"),
                    1.0d  // exact hash match == perfect similarity
            ));
        }, keyId, model, promptHash);
    }

    /**
     * Semantic search: finds the single most similar non-expired entry for
     * this key + model, regardless of whether it meets the threshold.
     *
     * Cosine similarity = 1 - (embedding <=> query). pgvector's &lt;=&gt; operator
     * returns cosine distance in [0, 2].
     *
     * Returns empty only when there is no stored entry at all for this
     * key + model. Otherwise returns the best candidate's similarity so the
     * caller (SemanticCacheService) can log "closest match was 0.87, needed
     * 0.95" instead of a bare miss with no diagnostic signal.
     */
    public Optional<SimilarityCandidate> findMostSimilar(
            UUID keyId,
            String model,
            float[] embedding
    ) {

        String vectorLiteral = toVectorLiteral(embedding);

        String sql = """
                SELECT id,
                       response,
                       input_tokens,
                       output_tokens,
                       1 - (embedding <=> ?::vector) AS similarity
                FROM prism.semantic_cache
                WHERE key_id = ?
                  AND model = ?
                  AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                ORDER BY embedding <=> ?::vector
                LIMIT 1
                """;

        return jdbcTemplate.query(sql, rs -> {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(new SimilarityCandidate(
                    new CacheEntry(
                            rs.getObject("id", UUID.class),
                            rs.getString("response"),
                            rs.getLong("input_tokens"),
                            rs.getLong("output_tokens"),
                            rs.getDouble("similarity")
                    ),
                    rs.getDouble("similarity")
            ));
        }, vectorLiteral, keyId, model, vectorLiteral);
    }

    public void save(
            UUID keyId,
            String model,
            String promptHash,
            String promptText,
            float[] embedding,
            String response,
            long inputTokens,
            long outputTokens,
            Instant expiresAt
    ) {

        String sql = """
                INSERT INTO prism.semantic_cache (
                    id, key_id, model, prompt_hash, prompt_text,
                    embedding, response, input_tokens, output_tokens, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?::vector, ?, ?, ?, ?)
                """;

        jdbcTemplate.update(
                sql,
                UUID.randomUUID(),
                keyId,
                model,
                promptHash,
                promptText,
                toVectorLiteral(embedding),
                response,
                inputTokens,
                outputTokens,
                expiresAt != null ? Timestamp.from(expiresAt) : null
        );
    }

    public void incrementHitCount(UUID id) {
        jdbcTemplate.update(
                "UPDATE prism.semantic_cache SET hit_count = hit_count + 1 WHERE id = ?",
                id
        );
    }

    /** Number of distinct cache entries stored for a key. */
    public long countEntries(UUID keyId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prism.semantic_cache WHERE key_id = ?",
                Long.class, keyId
        );
        return count != null ? count : 0L;
    }

    /** Sum of hit_count across all entries for a key (total cache hits served). */
    public long totalHits(UUID keyId) {
        Long hits = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(hit_count), 0) FROM prism.semantic_cache WHERE key_id = ?",
                Long.class, keyId
        );
        return hits != null ? hits : 0L;
    }

    /**
     * Hits and misses for a key, derived from the authoritative request_logs
     * table (every successful request records cache_hit true/false).
     * Index 0 = hits, index 1 = misses.
     */
    public long[] hitsAndMisses(UUID keyId) {
        return jdbcTemplate.query(
                """
                SELECT
                    COUNT(*) FILTER (WHERE cache_hit)       AS hits,
                    COUNT(*) FILTER (WHERE NOT cache_hit
                                     AND status = 'success') AS misses
                FROM prism.request_logs
                WHERE key_id = ?
                """,
                rs -> {
                    if (rs.next()) {
                        return new long[]{ rs.getLong("hits"), rs.getLong("misses") };
                    }
                    return new long[]{ 0L, 0L };
                },
                keyId
        );
    }

    /** Formats a float[] as a pgvector literal: [0.1,0.2,0.3] */
    private String toVectorLiteral(float[] vector) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (float v : vector) {
            joiner.add(Float.toString(v));
        }
        return joiner.toString();
    }

    public record CacheEntry(
            UUID id,
            String response,
            long inputTokens,
            long outputTokens,
            double similarity
    ) {}

    /** The closest stored entry for a lookup, and its similarity score — whether or not it clears the caller's threshold. */
    public record SimilarityCandidate(CacheEntry entry, double similarity) {}
}
