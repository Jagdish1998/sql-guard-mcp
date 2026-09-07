-- Fixture schema for the integration tests. Deliberately contains columns the redactor
-- should catch (email, password_hash, phone) next to ordinary ones, so a test can prove
-- masking happens without needing a contrived table.

CREATE TABLE customers (
    id             INT PRIMARY KEY,
    name           VARCHAR(100) NOT NULL,
    email          VARCHAR(200) NOT NULL,
    phone          VARCHAR(30),
    password_hash  VARCHAR(200),
    country        VARCHAR(2)
);

CREATE TABLE orders (
    id           INT PRIMARY KEY,
    customer_id  INT NOT NULL REFERENCES customers (id),
    status       VARCHAR(20) NOT NULL,
    total        DECIMAL(10, 2) NOT NULL,
    placed_at    TIMESTAMP NOT NULL
);

CREATE VIEW order_summary AS
SELECT c.name, count(o.id) AS order_count, sum(o.total) AS lifetime_value
FROM customers c
         LEFT JOIN orders o ON o.customer_id = c.id
GROUP BY c.name;
