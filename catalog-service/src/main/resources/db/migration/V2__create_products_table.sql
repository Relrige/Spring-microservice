CREATE TABLE products (
    id UUID PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(5000) NOT NULL,
    category_id UUID NOT NULL,
    base_price NUMERIC(12, 2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    stock_status VARCHAR(20) NOT NULL,
    -- A category that still has products cannot be deleted
    CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories (id) ON DELETE RESTRICT,
    CONSTRAINT products_base_price_positive CHECK (base_price > 0),
    CONSTRAINT products_status_check CHECK (status IN ('ACTIVE', 'NOT_ACTIVE')),
    CONSTRAINT products_stock_status_check CHECK (stock_status IN ('IN_STOCK', 'OUT_OF_STOCK'))
);

-- Browse queries filter by category and by status (EP-CAT-04)
CREATE INDEX idx_products_category_id ON products (category_id);
CREATE INDEX idx_products_status ON products (status);
