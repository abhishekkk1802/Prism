package com.prism.gateway.concurrency;

import com.prism.gateway.repository.UsageMonthlyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 11 — concurrent budget admission regression test.
 *
 * Fires many concurrent {@code settleWithinBudget} calls (released together via
 * a CyclicBarrier to maximise contention) against a dedicated test API key
 * whose budget only permits a known number of charges, then asserts against the
 * authoritative PostgreSQL state that:
 *   - the number of admitted calls == floor(budget / cost)
 *   - total_cost_usd never exceeds the budget
 *
 * Requires a live PostgreSQL (every @SpringBootTest in this project does).
 * Uses a dedicated, randomly-named key that is created in setup and deleted in
 * teardown, so no development data is touched.
 */
@SpringBootTest
class BudgetConcurrencyTest {

    @Autowired
    private UsageMonthlyRepository usageMonthlyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID keyId;
    private final LocalDate month = LocalDate.now().withDayOfMonth(1);

    // Budget allows exactly 5 charges of $0.0004 ( = $0.0020 ), the 6th overspends.
    private static final BigDecimal COST = new BigDecimal("0.000400");
    private static final BigDecimal BUDGET = new BigDecimal("0.002000");
    private static final int EXPECTED_ADMITTED = 5;
    private static final int CONCURRENCY = 40;

    @BeforeEach
    void setUp() {
        keyId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO prism.api_keys (id, key_hash, name, active, monthly_budget_usd)
                VALUES (?, ?, ?, TRUE, ?)
                """,
                keyId,
                "test-" + keyId,          // unique hash, never a real key
                "phase11-budget-test",
                BUDGET
        );
    }

    @AfterEach
    void tearDown() {
        // Delete only this test key's rows; dev data is untouched.
        jdbcTemplate.update("DELETE FROM prism.usage_monthly WHERE key_id = ?", keyId);
        jdbcTemplate.update("DELETE FROM prism.request_logs WHERE key_id = ?", keyId);
        jdbcTemplate.update("DELETE FROM prism.api_keys WHERE id = ?", keyId);
    }

    @Test
    void concurrentSettlesNeverExceedBudget() throws Exception {
        AtomicInteger admitted = new AtomicInteger();
        CyclicBarrier barrier = new CyclicBarrier(CONCURRENCY);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);

        try {
            List<CompletableFuture<Void>> futures = java.util.stream.IntStream.range(0, CONCURRENCY)
                    .mapToObj(i -> CompletableFuture.runAsync(() -> {
                        try {
                            barrier.await(); // release all threads at once
                            boolean ok = usageMonthlyRepository.settleWithinBudget(
                                    keyId, month, 10, 20, COST, false, BUDGET
                            );
                            if (ok) {
                                admitted.incrementAndGet();
                            }
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }, pool))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // Authoritative DB state
        BigDecimal finalSpend = usageMonthlyRepository.getMonthlySpend(keyId, month);
        Long requests = jdbcTemplate.queryForObject(
                "SELECT requests FROM prism.usage_monthly WHERE key_id = ? AND month = ?",
                Long.class, keyId, month
        );

        // Exactly floor(budget/cost) admitted — no over-admission, no lost updates.
        assertEquals(EXPECTED_ADMITTED, admitted.get(),
                "admitted calls should equal floor(budget/cost)");
        assertEquals((long) EXPECTED_ADMITTED, requests,
                "DB requests counter must match admitted calls (no lost updates)");

        // Never overspent.
        assertTrue(finalSpend.compareTo(BUDGET) <= 0,
                "final spend " + finalSpend + " must not exceed budget " + BUDGET);
        assertEquals(0, finalSpend.compareTo(
                COST.multiply(BigDecimal.valueOf(EXPECTED_ADMITTED))),
                "final spend must equal exactly admitted * cost");
    }

    @Test
    void concurrentSettlesHaveNoLostTokenUpdates() throws Exception {
        // Unlimited budget path: every call must settle and token counters must
        // sum exactly (atomic increment, no lost updates).
        final int n = 50;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);

        try {
            List<CompletableFuture<Void>> futures = java.util.stream.IntStream.range(0, n)
                    .mapToObj(i -> CompletableFuture.runAsync(() -> {
                        try {
                            barrier.await();
                            // null budget = unlimited -> always settles
                            usageMonthlyRepository.settleWithinBudget(
                                    keyId, month, 10, 20, new BigDecimal("0.000001"), false, null
                            );
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }, pool))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        Long requests = jdbcTemplate.queryForObject(
                "SELECT requests FROM prism.usage_monthly WHERE key_id = ? AND month = ?",
                Long.class, keyId, month);
        Long inTokens = jdbcTemplate.queryForObject(
                "SELECT input_tokens FROM prism.usage_monthly WHERE key_id = ? AND month = ?",
                Long.class, keyId, month);
        Long outTokens = jdbcTemplate.queryForObject(
                "SELECT output_tokens FROM prism.usage_monthly WHERE key_id = ? AND month = ?",
                Long.class, keyId, month);

        assertEquals((long) n, requests, "requests counter (expected no lost updates)");
        assertEquals(10L * n, inTokens, "input_tokens sum (expected no lost updates)");
        assertEquals(20L * n, outTokens, "output_tokens sum (expected no lost updates)");
    }
}
