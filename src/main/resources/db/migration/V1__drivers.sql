CREATE TABLE drivers (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    phone VARCHAR(30) NOT NULL UNIQUE,
    vehicle_number VARCHAR(30) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO drivers (id, name, phone, vehicle_number)
SELECT n, 'Demo Driver ' || n,
       '+9190000000' || LPAD(n::text, 2, '0'),
       'DL01DEMO' || LPAD(n::text, 2, '0')
FROM generate_series(1, 10) AS n;
