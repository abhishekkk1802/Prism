package com.prism.gateway.service;

import com.prism.gateway.repository.ModelPriceRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class CostCalculator {

    private static final BigDecimal ONE_MILLION =
            BigDecimal.valueOf(1_000_000);

    private final ModelPriceRepository modelPriceRepository;

    public CostCalculator(ModelPriceRepository modelPriceRepository) {
        this.modelPriceRepository = modelPriceRepository;
    }

    public BigDecimal calculate(
            String model,
            long inputTokens,
            long outputTokens
    ) {

        ModelPriceRepository.ModelPrice price =
                modelPriceRepository.findByModel(model)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "No pricing configured for model: "
                                                + model
                                )
                        );

        BigDecimal inputCost =
                price.inputPricePer1M()
                        .multiply(BigDecimal.valueOf(inputTokens))
                        .divide(
                                ONE_MILLION,
                                12,
                                RoundingMode.HALF_UP
                        );

        BigDecimal outputCost =
                price.outputPricePer1M()
                        .multiply(BigDecimal.valueOf(outputTokens))
                        .divide(
                                ONE_MILLION,
                                12,
                                RoundingMode.HALF_UP
                        );

        return inputCost
                .add(outputCost)
                .setScale(6, RoundingMode.HALF_UP);
    }
}