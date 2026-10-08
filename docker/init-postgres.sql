-- Runs once when the postgres container initializes an EMPTY data directory
-- (via docker-entrypoint-initdb.d), before the gateway's Flyway migrations
-- execute. Enables pgvector so V8__create_semantic_cache.sql's `vector(1536)`
-- columns can be created on a fresh database.
CREATE EXTENSION IF NOT EXISTS vector;
