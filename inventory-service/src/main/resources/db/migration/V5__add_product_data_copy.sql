-- Copy of Catalog's product data (D8): written only by the version-guarded upsert in StockItemRepository.
-- NULL = not copied yet (rows from before this revision, or from an event without a version).
ALTER TABLE stock_items
    ADD COLUMN title VARCHAR(255),
    ADD COLUMN product_status VARCHAR(20) CHECK (product_status IN ('ACTIVE', 'NOT_ACTIVE')),
    ADD COLUMN product_version BIGINT;
