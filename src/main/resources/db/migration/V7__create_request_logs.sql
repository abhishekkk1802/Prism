CREATE TABLE prism.request_logs (
    id              UUID                     PRIMARY KEY,
    key_id          UUID                     NOT NULL REFERENCES prism.api_keys(id),
    request_id      VARCHAR(64)              NOT NULL,
    requested_model VARCHAR(100)             NOT NULL,
    chosen_tier     VARCHAR(100),
    routing_reason  VARCHAR(50),
    provider        VARCHAR(100),
    final_model     VARCHAR(100),
    status          VARCHAR(20)              NOT NULL,
    input_tokens    BIGINT                   NOT NULL DEFAULT 0,
    output_tokens   BIGINT                   NOT NULL DEFAULT 0,
    cost_usd        NUMERIC(12,6)            NOT NULL DEFAULT 0,
    cache_hit       BOOLEAN                  NOT NULL DEFAULT FALSE,
    fallback        BOOLEAN                  NOT NULL DEFAULT FALSE,
    retries         INTEGER                  NOT NULL DEFAULT 0,
    latency_ms      BIGINT                   NOT NULL DEFAULT 0,
    error_message   TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_request_logs_key_id     ON prism.request_logs(key_id);
CREATE INDEX idx_request_logs_created_at ON prism.request_logs(created_at);
