CREATE TABLE orders (
	id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	project_id BIGINT NOT NULL REFERENCES projects (id) ON DELETE RESTRICT,
	quantity_units INT NOT NULL CHECK (quantity_units > 0),
	priority VARCHAR(16) NOT NULL CHECK (priority IN ('LOW', 'NORMAL', 'HIGH')),
	required_date DATE NOT NULL,
	status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CANCELLED', 'COMPLETED')),
	created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE order_lines (
	id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	order_id BIGINT NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
	part_id BIGINT NOT NULL REFERENCES parts (id) ON DELETE RESTRICT,
	required_quantity INT NOT NULL CHECK (required_quantity > 0),
	reserved_quantity INT NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0 AND reserved_quantity <= required_quantity),
	UNIQUE (order_id, part_id)
);
