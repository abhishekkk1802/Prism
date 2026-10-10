INSERT INTO prism.model_prices
    (id, model, input_price_per_1m, output_price_per_1m)
VALUES
    (gen_random_uuid(), 'alpha-small', 0.150000, 0.600000),
    (gen_random_uuid(), 'alpha-large', 2.500000, 10.000000),
    (gen_random_uuid(), 'beta-small', 0.200000, 0.800000),
    (gen_random_uuid(), 'beta-large', 3.000000, 12.000000)
ON CONFLICT (model) DO NOTHING;
