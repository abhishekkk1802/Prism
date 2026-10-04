package com.prism.gateway.service;

import com.prism.gateway.exception.BudgetExceededException;
import com.prism.gateway.repository.UsageMonthlyRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class DefaultBudgetAdmissionService
        implements BudgetAdmissionService {

    private final UsageMonthlyRepository usageMonthlyRepository;

    public DefaultBudgetAdmissionService(
            UsageMonthlyRepository usageMonthlyRepository
    ) {
        this.usageMonthlyRepository = usageMonthlyRepository;
    }

    @Override
    public void checkAndReserve(
            UUID keyId,
            BigDecimal monthlyBudget,
            BigDecimal estimatedCost,
            String requestId
    ) {

        if (monthlyBudget == null) {
            return;
        }

        LocalDate month =
                LocalDate.now().withDayOfMonth(1);

        BigDecimal currentSpend =
                usageMonthlyRepository.getMonthlySpend(
                        keyId,
                        month
                );

        BigDecimal projectedSpend =
                currentSpend.add(estimatedCost);

        if (projectedSpend.compareTo(monthlyBudget) > 0) {

            throw new BudgetExceededException(
                    "Monthly budget exceeded"
            );
        }

        /*
         * Redis budget reservation will be added here.
         *
         * budget-hold:{requestId}
         *
         * This prevents concurrent requests from
         * simultaneously consuming the remaining budget.
         */
    }

    @Override
    public void settle(
            UUID keyId,
            String requestId,
            long inputTokens,
            long outputTokens,
            BigDecimal actualCost,
            boolean cacheHit
    ) {

        LocalDate month =
                LocalDate.now().withDayOfMonth(1);

        usageMonthlyRepository.settle(
                keyId,
                month,
                inputTokens,
                outputTokens,
                actualCost,
                cacheHit
        );

        /*
         * Redis budget hold will be removed here.
         */
    }
}