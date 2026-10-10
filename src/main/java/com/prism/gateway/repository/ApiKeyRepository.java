package com.prism.gateway.repository;

import com.prism.gateway.service.ApiKeyDetails;
import com.prism.gateway.service.ApiKeyPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ApiKeyRepository {

    private final JdbcTemplate jdbcTemplate;

    public ApiKeyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ApiKeyPolicy> findActivePolicy(String keyHash) {

        String sql = """
            SELECT
                id,
                name,
                team,
                monthly_budget_usd,
                rpm_limit,
                allowed_models,
                cache_enabled,
                cache_similarity_threshold
            FROM prism.api_keys
            WHERE key_hash = ?
              AND active = true
            """;

        return jdbcTemplate.query(
                sql,
                rs -> {
                    if (!rs.next()) {
                        return Optional.empty();
                    }

                    String[] models = rs.getArray("allowed_models") != null
                            ? (String[]) rs.getArray("allowed_models").getArray()
                            : new String[0];

                    return Optional.of(
                            new ApiKeyPolicy(
                                    UUID.fromString(rs.getString("id")),
                                    rs.getString("name"),
                                    rs.getString("team"),
                                    rs.getBigDecimal("monthly_budget_usd"),
                                    (Integer) rs.getObject("rpm_limit"),
                                    Arrays.asList(models),
                                    (Boolean) rs.getObject("cache_enabled"),
                                    rs.getBigDecimal("cache_similarity_threshold")
                            )
                    );
                },
                keyHash
        );
    }

    public Optional<ApiKeyDetails> findActiveKeyDetails(String keyHash) {

        String sql = """
            SELECT id, rpm_limit
            FROM prism.api_keys
            WHERE key_hash = ?
              AND active = true
            """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new ApiKeyDetails(
                        UUID.fromString(rs.getString("id")),
                        rs.getInt("rpm_limit")
                ),
                keyHash
        ).stream().findFirst();
    }

}