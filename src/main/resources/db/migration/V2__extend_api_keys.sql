ALTER TABLE prism.api_keys
    ADD COLUMN team VARCHAR(100),
    ADD COLUMN monthly_budget_usd NUMERIC(12, 6),
    ADD COLUMN rpm_limit INTEGER,
    ADD COLUMN allowed_models TEXT[],
    ADD COLUMN cache_enabled BOOLEAN,
    ADD COLUMN cache_similarity_threshold NUMERIC(5, 4);