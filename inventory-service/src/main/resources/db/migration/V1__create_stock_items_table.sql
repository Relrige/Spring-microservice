CREATE TABLE IF NOT EXISTS stock_items (
    product_id UUID PRIMARY KEY,
    available_quantity INTEGER NOT NULL CHECK(available_quantity >= 0) DEFAULT 0,
    reserved_quantity INTEGER NOT NULL CHECK(reserved_quantity >= 0) DEFAULT 0,
    gross_weight_grams INTEGER CHECK(gross_weight_grams > 0),
    length_cm INTEGER CHECK(length_cm > 0),
    width_cm INTEGER CHECK(width_cm > 0),
    height_cm INTEGER CHECK(height_cm > 0)
);