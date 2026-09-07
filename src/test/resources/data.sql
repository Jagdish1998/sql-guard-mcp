INSERT INTO customers (id, name, email, phone, password_hash, country) VALUES
    (1, 'Alice Kumar',   'alice@example.com', '+919876543210', 'bcrypt$2a$aaaaaaaa', 'IN'),
    (2, 'Bob Mensah',    'bob@example.com',   '+233201234567', 'bcrypt$2a$bbbbbbbb', 'GH'),
    (3, 'Carol Silva',   'carol@example.com', '+5511987654321','bcrypt$2a$cccccccc', 'BR'),
    (4, 'Dan Okafor',    'dan@example.com',   '+2348012345678','bcrypt$2a$dddddddd', 'NG'),
    (5, 'Eve Nakamura',  'eve@example.com',   '+819012345678', 'bcrypt$2a$eeeeeeee', 'JP');

INSERT INTO orders (id, customer_id, status, total, placed_at) VALUES
    (1,  1, 'SHIPPED',   120.50, TIMESTAMP '2026-01-04 10:15:00'),
    (2,  1, 'SHIPPED',    89.99, TIMESTAMP '2026-01-19 14:02:00'),
    (3,  2, 'PENDING',    45.00,  TIMESTAMP '2026-02-02 09:30:00'),
    (4,  2, 'CANCELLED',  15.75,  TIMESTAMP '2026-02-11 16:45:00'),
    (5,  3, 'SHIPPED',   310.00,  TIMESTAMP '2026-02-20 11:00:00'),
    (6,  3, 'SHIPPED',    22.40,  TIMESTAMP '2026-03-01 08:20:00'),
    (7,  4, 'PENDING',   199.99,  TIMESTAMP '2026-03-15 19:05:00'),
    (8,  4, 'SHIPPED',    64.30,  TIMESTAMP '2026-03-22 13:10:00'),
    (9,  5, 'SHIPPED',   540.00,  TIMESTAMP '2026-04-02 07:55:00'),
    (10, 5, 'PENDING',    12.99,  TIMESTAMP '2026-04-09 21:40:00');
