-- Optional image of a product (task 30): an https URL or a path under /images/ served by the frontend.
ALTER TABLE products ADD COLUMN image_url VARCHAR(2048);
