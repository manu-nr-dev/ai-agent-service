-- Run this in your PostgreSQL instance
-- createdb agent_db  (or create via pgAdmin)

CREATE TABLE IF NOT EXISTS products (
    id       SERIAL PRIMARY KEY,
    name     VARCHAR(100) NOT NULL,
    price    NUMERIC(10, 2) NOT NULL,
    category VARCHAR(50) NOT NULL
);

-- Sample data
INSERT INTO products (name, price, category) VALUES
('Laptop Pro 15',     89999.00, 'Electronics'),
('Wireless Mouse',     1299.00, 'Electronics'),
('Standing Desk',     24999.00, 'Furniture'),
('Ergonomic Chair',   18999.00, 'Furniture'),
('Noise Cancelling Headphones', 7999.00, 'Electronics'),
('Mechanical Keyboard', 5499.00, 'Electronics'),
('Monitor 27 inch',  32999.00, 'Electronics'),
('Desk Lamp',          899.00, 'Furniture'),
('Webcam HD',         3499.00, 'Electronics'),
('Whiteboard',        4999.00, 'Furniture');
