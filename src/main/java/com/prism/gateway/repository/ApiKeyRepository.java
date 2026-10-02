package com.prism.gateway.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class ApiKeyRepository {

    private final JdbcTemplate jdbcTemplate;

    public ApiKeyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<UUID> findActiveKey(String keyHash){
        String sql = """
                SELECT id from prism.api_keys
                where key_hash = ?
                and active = true
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) ->UUID.fromString(rs.getString("id")),
                keyHash
        ).stream().findFirst();
    }

}