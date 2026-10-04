CREATE TABLE IF NOT EXISTS prism.usage_monthly (
    id UUID PRIMARY KEY,
    key_id UUID NOT NULL REFERENCES prism.api_keys(id),
    month DATE NOT NULL,
    requests BIGINT NOT NULL DEFAULT 0,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    total_cost_usd NUMERIC(12,6) NOT NULL DEFAULT 0,
    cache_hits BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT usage_monthly_key_month_unique
        UNIQUE (key_id, month)
);
isModelAllowed