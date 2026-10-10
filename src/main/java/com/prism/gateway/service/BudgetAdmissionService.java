package com.prism.gateway.service;

import java.math.BigDecimal;
import java.util.UUID;

public interface BudgetAdmissionService {

    void checkAndReserve(
            UUID keyId,
            BigDecimal monthlyBudget,
            BigDecimal estimatedCost,
            String requestId
    );

    void settle(
            UUID keyId,
            String requestId,
            long inputTokens,
            long outputTokens,
            BigDecimal actualCost,
            boolean cacheHit
    );

    /**
     * Atomically settles usage only if it keeps the key within its monthly
     * budget. The budget check and the usage increment happen in a single
     * database statement, closing the read-then-write race in {@link
     * #checkAndReserve}. Returns true if admitted/settled, false if the request
     * would exceed the budget (no state change). A null budget means unlimited.
     */
    boolean settleWithinBudget(
            UUID keyId,
            String requestId,
            long inputTokens,
            long outputTokens,
            BigDecimal actualCost,
            boolean cacheHit,
            BigDecimal monthlyBudget
    );
}