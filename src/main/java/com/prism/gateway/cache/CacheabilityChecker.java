package com.prism.gateway.cache;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Decides whether a prompt is safe to serve from / store in the semantic cache.
 *
 * Clearly time-sensitive prompts (current prices, today's weather, latest news,
 * "right now", etc.) must never be answered from a stale cached response, so
 * they bypass the cache entirely. This is a deliberately small, isolated
 * heuristic — not an LLM judge — and can be swapped out later without touching
 * the cache service or the orchestration flow.
 */
@Component
public class CacheabilityChecker {

    private static final Pattern TIME_SENSITIVE = Pattern.compile(
            "\\b(today|now|current|currently|latest|right now|this (week|month|year)|"
            + "yesterday|tomorrow|real[- ]?time|live|at the moment|up[- ]?to[- ]?date|"
            + "breaking|news|price|stock|exchange rate|weather|forecast)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * @return true if the prompt may be cached and served from cache,
     *         false if it is time-sensitive and must bypass the cache.
     */
    public boolean isCacheable(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        return !TIME_SENSITIVE.matcher(prompt).find();
    }
}
