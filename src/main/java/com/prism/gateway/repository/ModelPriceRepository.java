package com.prism.gateway.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

@Repository
public class ModelPriceRepository {

    private final JdbcTemplate jdbcTemplate;

    public ModelPriceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ModelPrice> findByModel(String model) {

        String sql = """
                SELECT model,
                       input_price_per_1m,
                       output_price_per_1m
                FROM prism.model_prices
                WHERE model = ?
                """;

        return jdbcTemplate.query(
                sql,
                rs -> {
                    if (!rs.next()) {
                        return Optional.empty();
                    }

                    return Optional.of(
                            new ModelPrice(
                                    rs.getString("model"),
                                    rs.getBigDecimal("input_price_per_1m"),
                                    rs.getBigDecimal("output_price_per_1m")
                            )
                    );
                },
                model
        );
    }

    public record ModelPrice(
            String model,
            BigDecimal inputPricePer1M,
            BigDecimal outputPricePer1M
    ) {}
}