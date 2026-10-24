-- Optimistic locking (JPA @Version) and the order of product events: consumers keep the state with the highest
-- version. Existing products start at 0. The stock-status read-model update does not change it.
ALTER TABLE products ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
