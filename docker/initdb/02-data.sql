INSERT INTO customers (name, email, phone, password_hash, country) VALUES
    ('Alice Kumar',   'alice@example.com',   '+919876543210',  'bcrypt$2a$aaaaaaaaaaaa', 'IN'),
    ('Bob Mensah',    'bob@example.com',     '+233201234567',  'bcrypt$2a$bbbbbbbbbbbb', 'GH'),
    ('Carol Silva',   'carol@example.com',   '+5511987654321', 'bcrypt$2a$cccccccccccc', 'BR'),
    ('Dan Okafor',    'dan@example.com',     '+2348012345678', 'bcrypt$2a$dddddddddddd', 'NG'),
    ('Eve Nakamura',  'eve@example.com',     '+819012345678',  'bcrypt$2a$eeeeeeeeeeee', 'JP'),
    ('Farid Haddad',  'farid@example.com',   '+201234567890',  'bcrypt$2a$ffffffffffff', 'EG'),
    ('Grace Lin',     'grace@example.com',   '+8613800138000', 'bcrypt$2a$gggggggggggg', 'CN'),
    ('Hugo Martins',  'hugo@example.com',    '+351912345678',  'bcrypt$2a$hhhhhhhhhhhh', 'PT');

INSERT INTO products (sku, name, price, in_stock) VALUES
    ('KB-001', 'Mechanical Keyboard',   129.00, 42),
    ('MS-002', 'Wireless Mouse',         49.50, 130),
    ('MN-003', '27-inch Monitor',       310.00, 18),
    ('HS-004', 'Noise Cancelling Headset', 199.99, 7),
    ('DK-005', 'Standing Desk',         540.00, 3),
    ('CB-006', 'USB-C Cable',            12.99, 500);

INSERT INTO orders (customer_id, status, total, placed_at) VALUES
    (1, 'SHIPPED',   178.50, '2026-01-04 10:15:00+00'),
    (1, 'SHIPPED',    49.50, '2026-01-19 14:02:00+00'),
    (2, 'PENDING',   129.00, '2026-02-02 09:30:00+00'),
    (2, 'CANCELLED',  12.99, '2026-02-11 16:45:00+00'),
    (3, 'SHIPPED',   310.00, '2026-02-20 11:00:00+00'),
    (3, 'SHIPPED',    62.49, '2026-03-01 08:20:00+00'),
    (4, 'PENDING',   199.99, '2026-03-15 19:05:00+00'),
    (4, 'SHIPPED',   540.00, '2026-03-22 13:10:00+00'),
    (5, 'SHIPPED',   129.00, '2026-04-02 07:55:00+00'),
    (5, 'PENDING',    12.99, '2026-04-09 21:40:00+00'),
    (6, 'SHIPPED',   249.49, '2026-04-18 12:00:00+00'),
    (7, 'SHIPPED',   850.00, '2026-05-02 15:30:00+00'),
    (8, 'PENDING',    49.50, '2026-05-14 10:10:00+00');

INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES
    (1, 1, 1, 129.00), (1, 2, 1, 49.50),
    (2, 2, 1, 49.50),
    (3, 1, 1, 129.00),
    (4, 6, 1, 12.99),
    (5, 3, 1, 310.00),
    (6, 2, 1, 49.50), (6, 6, 1, 12.99),
    (7, 4, 1, 199.99),
    (8, 5, 1, 540.00),
    (9, 1, 1, 129.00),
    (10, 6, 1, 12.99),
    (11, 4, 1, 199.99), (11, 2, 1, 49.50),
    (12, 5, 1, 540.00), (12, 3, 1, 310.00),
    (13, 2, 1, 49.50);

INSERT INTO private.api_credentials (label, secret_key) VALUES
    ('stripe-live', 'sk_live_should_never_be_readable'),
    ('twilio',      'tw_should_never_be_readable');

INSERT INTO employee_salaries (employee, annual_usd) VALUES
    ('Alice Kumar', 145000.00),
    ('Bob Mensah',  132000.00);
