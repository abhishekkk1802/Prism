package com.prism.gateway.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ApiKeyPolicy(
        UUID id,
        String name,
        String team,
        BigDecimal monthlyBudgetUsd,
        Integer rpmLimit,
        List<String> allowedModels,
        Boolean cacheEnabled,
        BigDecimal cacheSimilarityThreshold
) {
}