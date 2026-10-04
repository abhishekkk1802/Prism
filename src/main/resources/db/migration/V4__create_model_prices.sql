CREATE TABLE prism.model_prices (
    id UUID PRIMARY KEY,
    model VARCHAR(100) NOT NULL UNIQUE,
    input_price_per_1m NUMERIC(12,6) NOT NULL,
    output_price_per_1m NUMERIC(12,6) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
