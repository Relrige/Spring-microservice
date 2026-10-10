CREATE TABLE categories (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL
);

-- Category names are unique ignoring case: "Laptops" and "laptops" cannot coexist
CREATE UNIQUE INDEX categories_name_lower_key ON categories (lower(name));
