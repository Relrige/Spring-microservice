-- The Cart aggregate. One cart per customer (customer_id is Auth's user ID); a cart row is created on the first
-- change that may add items and is never deleted (clearing a cart deletes only its items)
CREATE TABLE carts
(
    customer_id UUID PRIMARY KEY,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);

-- INV-CART-02 is repeated here as a safety net; both invariants are enforced by the Cart aggregate
CREATE TABLE cart_items
(
    cart_id    UUID NOT NULL REFERENCES carts (customer_id),
    product_id UUID NOT NULL,
    quantity   INT  NOT NULL CHECK (quantity BETWEEN 1 AND 10),
    PRIMARY KEY (cart_id, product_id)
);
