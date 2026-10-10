
CREATE TABLE prism.semantic_cache (
    id           UUID                     PRIMARY KEY,
    key_id       UUID                     NOT NULL REFERENCES prism.api_keys(id),
    model        VARCHAR(100)             NOT NULL,
    prompt_hash  VARCHAR(64)              NOT NULL,
    prompt_text  TEXT                     NOT NULL,
    embedding    vector(1536)             NOT NULL,
    response     TEXT                     NOT NULL,
    input_tokens  BIGINT                  NOT NULL DEFAULT 0,
    output_tokens BIGINT                  NOT NULL DEFAULT 0,
    hit_count    BIGINT                   NOT NULL DEFAULT 0,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at   TIMESTAMP WITH TIME ZONE
);

-- Per-key + model lookup narrowing before vector search
CREATE INDEX idx_semantic_cache_key_model
    ON prism.semantic_cache (key_id, model);

-- Exact-match fast path
CREATE INDEX idx_semantic_cache_hash
    ON prism.semantic_cache (key_id, model, prompt_hash);

-- Approximate nearest-neighbour search over cosine distance
CREATE INDEX idx_semantic_cache_embedding
    ON prism.semantic_cache
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);
