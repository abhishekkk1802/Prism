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
}