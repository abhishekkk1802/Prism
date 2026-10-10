-- Seed the virtual API keys referenced by the Prism evaluation guide
-- (scripts/load_test.py, scripts/smoke_test.py, data/seed_keys.json).
-- Raw key values (never stored): prism-sk-free-7g8h9i, prism-sk-budget-demo-0j1k2l,
-- prism-sk-platform-3k4l5m. Only their SHA-256 hashes are persisted, matching
-- com.prism.gateway.security.TokenHasher.sha256(...).
INSERT INTO prism.api_keys
    (id, key_hash, name, active, team, monthly_budget_usd, rpm_limit, allowed_models, cache_enabled, cache_similarity_threshold)
VALUES
    ('a1b2c3d4-0001-4000-8000-000000000001', '101e0c97a0206f12dc10d1103887247417df941e2e76e466d8ede7e54490d506', 'free-tier-eval-key', TRUE, 'free-tier', 5.000000, 10, '{fast,auto}', true, 0.9200),
    ('a1b2c3d4-0002-4000-8000-000000000002', '93a53a6d9a41eae5ebe7eab5e699cdd5218c6b58668b7745c552f58181d451cd', 'budget-demo-eval-key', TRUE, 'budget-demo', 0.000010, 60, '{fast,smart,auto}', false, 0.9000),
    ('a1b2c3d4-0003-4000-8000-000000000003', 'adf09011f95594b49666ddb2d36e9fad7bd8dbb3cde474e32ab0c99ee5da1091', 'platform-eval-key', TRUE, 'platform', 250.000000, 120, '{fast,smart,auto}', true, 0.9500);
