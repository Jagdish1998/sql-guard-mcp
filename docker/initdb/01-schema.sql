-- Demo schema. Includes a `private` schema and an obviously sensitive table so the
-- allow-list and blocklist have something real to exclude.

CREATE TABLE customers (
    id            SERIAL PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    email         VARCHAR(200) NOT NULL,
    phone         VARCHAR(30),
    password_hash VARCHAR(200),
    country       CHAR(2),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE products (
    id       SERIAL PRIMARY KEY,
    sku      VARCHAR(40) NOT NULL UNIQUE,
    name     VARCHAR(200) NOT NULL,
    price    NUMERIC(10, 2) NOT NULL,
    in_stock INT NOT NULL DEFAULT 0
);

CREATE TABLE orders (
    id          SERIAL PRIMARY KEY,
    customer_id INT NOT NULL REFERENCES customers (id),
    status      VARCHAR(20) NOT NULL,
    total       NUMERIC(10, 2) NOT NULL,
    placed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE order_items (
    id         SERIAL PRIMARY KEY,
    order_id   INT NOT NULL REFERENCES orders (id),
    product_id INT NOT NULL REFERENCES products (id),
    quantity   INT NOT NULL,
    unit_price NUMERIC(10, 2) NOT NULL
);

CREATE INDEX idx_orders_customer ON orders (customer_id);
CREATE INDEX idx_orders_status ON orders (status);
CREATE INDEX idx_order_items_order ON order_items (order_id);

CREATE VIEW customer_lifetime_value AS
SELECT c.id,
       c.name,
       count(o.id)              AS order_count,
       coalesce(sum(o.total), 0) AS lifetime_value
FROM customers c
         LEFT JOIN orders o ON o.customer_id = c.id
GROUP BY c.id, c.name;

-- Outside the default allowed-schemas list, so the server hides it even from list_tables.
CREATE SCHEMA private;

CREATE TABLE private.api_credentials (
    id         SERIAL PRIMARY KEY,
    label      VARCHAR(100) NOT NULL,
    secret_key VARCHAR(200) NOT NULL
);

-- In `public` but on the blocked-tables list, to show the two mechanisms are different.
CREATE TABLE employee_salaries (
    id          SERIAL PRIMARY KEY,
    employee    VARCHAR(100) NOT NULL,
    annual_usd  NUMERIC(12, 2) NOT NULL
);
