-- Fixture schema for the integration tests. Deliberately contains columns the redactor
-- should catch (email, password_hash, phone) next to ordinary ones, so a test can prove
-- masking happens without needing a contrived table.
--
-- Every object is dropped first. On H2 that is redundant, because each test context gets a
-- fresh in-memory database. On PostgreSQL it is required: the server is shared across test
-- contexts, and a second context running this script would otherwise fail with
-- "relation already exists". Dropping also guarantees each context starts from the same
-- rows, so a test cannot pass only because an earlier one left data behind.

DROP VIEW IF EXISTS order_summary;
DROP TABLE IF EXISTS orders CASCADE;
DROP TABLE IF EXISTS customers CASCADE;

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
